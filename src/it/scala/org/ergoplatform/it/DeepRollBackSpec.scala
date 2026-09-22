package org.ergoplatform.it

import java.io.File
import java.util.concurrent.TimeoutException
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import org.ergoplatform.it.api.NodeApi.{NodeInfo, nodeInfoDecoder}
import org.ergoplatform.it.container.{Docker, IntegrationSuite, Node}
import org.ergoplatform.nodeView.history.ErgoHistoryUtils
import org.ergoplatform.it.util.{ConvergenceObservations, ConvergenceWatch, NodeSample, PeerSyncStatus}
import org.scalatest.freespec.AnyFreeSpec
import scala.async.Async
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class DeepRollBackSpec extends AnyFreeSpec with IntegrationSuite {

  val keepVersions = 350
  val chainLength = 50
  val delta = 150

  val localVolumeA = s"$localDataDir/node-rollback-spec/nodeA/data"
  val localVolumeB = s"$localDataDir/node-rollback-spec/nodeB/data"
  val remoteVolumeA = "/appA"
  val remoteVolumeB = "/appB"

  val (dirA, dirB) = (new File(localVolumeA), new File(localVolumeB))
  dirA.mkdirs(); dirB.mkdirs()

  val minerAConfig: Config = specialDataDirConfig(remoteVolumeA)
    .withFallback(shortInternalMinerPollingInterval)
    .withFallback(keepVersionsConfig(keepVersions))
    .withFallback(nodeSeedConfigs.head)
    .withFallback(allowLocalConfig)

  val minerBConfig: Config = specialDataDirConfig(remoteVolumeB)
    .withFallback(shortInternalMinerPollingInterval)
    .withFallback(keepVersionsConfig(keepVersions))
    .withFallback(nodeSeedConfigs.last)
    .withFallback(allowLocalConfig)

  val minerAConfigNonGen: Config = minerAConfig
    .withFallback(nonGeneratingPeerConfig)
    .withFallback(allowLocalConfig)

  val minerBConfigNonGen: Config = minerBConfig
    .withFallback(nonGeneratingPeerConfig)
    .withFallback(allowLocalConfig)

  private val observations = new ConvergenceObservations
  private val watches = scala.collection.mutable.ArrayBuffer.empty[ConvergenceWatch]

  private case class NodeSnapshot(info: Option[NodeInfo], peers: Option[Int] = None, answered: Boolean = false,
                                  syncStatuses: Option[Seq[PeerSyncStatus]] = None) {
    def sample(node: Node, docker: Docker): NodeSample =
      NodeSample(node.nodeName, System.currentTimeMillis(),
        if (answered) NodeSample.Running else docker.containerState(node.nodeInfo.containerId), answered,
        info.flatMap(_.bestHeaderHeightOpt), info.flatMap(_.bestBlockHeightOpt),
        info.flatMap(_.bestHeaderIdOpt), info.flatMap(_.bestBlockIdOpt), peers, info.flatMap(_.isMining), syncStatuses)
  }
  private val probes = scala.collection.mutable.Map.empty[
    Node, (observations.Probe[NodeInfo], observations.Probe[Int], observations.Probe[Seq[PeerSyncStatus]])]

  @volatile private var lastObservation = "No node pair observed"
  private var recentObservations = Vector.empty[String]

  private def remember(phase: String, label: String, endpoint: String, summary: String): Unit = synchronized {
    val observation = s"$phase $label $endpoint sampledAt=${System.currentTimeMillis()} $summary"
    recentObservations = (recentObservations :+ observation).takeRight(12)
    lastObservation = recentObservations.mkString("; ")
    log.info(observation)
  }

  private def snapshot(phase: String, label: String, node: Node, budget: FiniteDuration): Future[NodeSnapshot] = {
    val (statusProbe, peerProbe, syncProbe) = probes.getOrElseUpdate(node, (
      observations.probe(node.singleGet("/info", _.setRequestTimeout(5000))
        .map { r =>
          require(r.getStatusCode == 200, "Unexpected observation status")
          node.ergoJsonAnswerAs[NodeInfo](r.getResponseBody)
        }),
      observations.probe(node.singleGet("/peers/connected", _.setRequestTimeout(5000)).map { r =>
        require(r.getStatusCode == 200, "Unexpected observation status")
        node.ergoJsonAnswerAs[Json](r.getResponseBody).asArray.getOrElse(
          throw new IllegalArgumentException("Expected peer array")).size
      }),
      observations.probe(node.singleGet("/peers/syncInfo", _.setRequestTimeout(5000)).map { r =>
        require(r.getStatusCode == 200, "Unexpected observation status")
        PeerSyncStatus.fromJson(node.ergoJsonAnswerAs[Json](r.getResponseBody))
      })
    ))
    val status = statusProbe.sample(budget).map {
      case Right(info) =>
        val summary = Json.obj(
          "headersHeight" -> info.bestHeaderHeightOpt.map(Json.fromInt).getOrElse(Json.Null),
          "fullHeight" -> info.bestBlockHeightOpt.map(Json.fromInt).getOrElse(Json.Null),
          "bestHeaderId" -> info.bestHeaderIdOpt.map(ConvergenceObservations.headerId).map(Json.fromString).getOrElse(Json.Null),
          "bestFullHeaderId" -> info.bestBlockIdOpt.map(ConvergenceObservations.headerId).map(Json.fromString).getOrElse(Json.Null)
        )
        remember(phase, label, "status", summary.noSpaces)
        Some(info)
      case Left(error) =>
        remember(phase, label, "status", s"errorClass=$error")
        None
    }
    val peers = peerProbe.sample(budget).map { result =>
      val summary = result.fold(error => s"errorClass=$error", count => s"connectedPeerCount=$count")
      remember(phase, label, "peers", summary)
      result.toOption
    }
    val sync = syncProbe.sample(budget).map { result =>
      remember(phase, label, "syncInfo", result.fold(error => s"errorClass=$error", _.map(_.render).mkString("[", ",", "]")))
      result.toOption
    }
    status.zip(peers).zip(sync).map { case ((info, peerCount), ratings) =>
      NodeSnapshot(info, peerCount, answered = info.nonEmpty, ratings)
    }
  }

  private def observeNodes(
    phase: String,
    nodeA: Node,
    nodeB: Node,
    budget: FiniteDuration = 5.seconds
  ): Future[(NodeSnapshot, NodeSnapshot)] = {
    snapshot(phase, "A", nodeA, budget).zip(snapshot(phase, "B", nodeB, budget))
  }

  private def waitForSameBestBlock(
    nodeA: Node,
    nodeB: Node,
    minHeight: Int,
    timeout: FiniteDuration
  ): Future[(NodeInfo, NodeInfo)] = {
    // names the cause of a miss (equal-height tie, headers ahead of full blocks, a node down, ...) from
    // the observations this wait already makes; it issues no request of its own
    val watch = new ConvergenceWatch(docker, Seq(nodeA, nodeB))
    watches += watch
    observations.until(timeout.fromNow, 1.second, 5.seconds) { budget =>
      observeNodes("convergence", nodeA, nodeB, budget).map { case (a, b) =>
        watch.record(Seq(a.sample(nodeA, docker), b.sample(nodeB, docker)))
        (a, b)
      }
    } { case (a, b) =>
      a.info.exists(infoA => b.info.exists(infoB => ConvergenceObservations.sameBestBlock(infoA, infoB, minHeight)))
    }(
      watch.report(s"Nodes did not converge to the same best full block at height >= $minHeight; " +
        s"recent observations: $lastObservation")
    ).map { case (a, b) => (a.info.get, b.info.get) }.andThen { case _ => watch.close() }
  }

  "Deep rollback handling" in {

    val result: Future[Unit] = Async.async {

      // 1. Let nodeA mine and sync nodeB

      val minerAGen: Node = docker.startDevNetNode(minerAConfig,
        specialVolumeOpt = Some((localVolumeA, remoteVolumeA))).get

      val minerBGen: Node = docker.startDevNetNode(minerBConfigNonGen,
        specialVolumeOpt = Some((localVolumeB, remoteVolumeB))).get

      Async.await(minerAGen.waitForHeight(1))
      Async.await(minerBGen.waitForHeight(1))

      val genesisAGen = Async.await(minerAGen.headerIdsByHeight(ErgoHistoryUtils.GenesisHeight)).head
      val genesisBGen = Async.await(minerBGen.headerIdsByHeight(ErgoHistoryUtils.GenesisHeight)).head

      val minerAGenBestHeight = Async.await(minerAGen.fullHeight)
      val minerBGenBestHeight = Async.await(minerBGen.fullHeight)

      log.info("heightA: " + minerAGenBestHeight)
      log.info("heightB: " + minerBGenBestHeight)

      genesisAGen shouldBe genesisBGen
      Async.await(observeNodes("initial shared chain", minerAGen, minerBGen))

      // 2. Stop all nodes
      docker.stopNode(minerAGen.containerId)
      docker.stopNode(minerBGen.containerId)

      val minerAIsolated: Node = docker.startDevNetNode(DeepRollBackSpec.isolatedMiningConfig.withFallback(minerAConfig), isolatedPeersConfig,
        specialVolumeOpt = Some((localVolumeA, remoteVolumeA))).get

      // 1. Let nodeA mine `chainLength + delta` blocks in isolation
      Async.await(minerAIsolated.waitForHeight(chainLength + delta))

      val minerBIsolated: Node = docker.startDevNetNode(DeepRollBackSpec.isolatedMiningConfig.withFallback(minerBConfig), isolatedPeersConfig,
        specialVolumeOpt = Some((localVolumeB, remoteVolumeB))).get
      Async.await(observeNodes("isolated miners started", minerAIsolated, minerBIsolated))

      // 2. Let nodeB mine `chainLength` blocks in isolation
      Async.await(minerBIsolated.waitForHeight(chainLength, 100.millis))

      Async.await(minerAIsolated.connectedPeers) shouldBe empty
      Async.await(minerBIsolated.connectedPeers) shouldBe empty

      log.info("Mining phase done")

      val minerABestHeight = Async.await(minerAIsolated.fullHeight)
      val minerBBestHeight = Async.await(minerBIsolated.fullHeight)
      Async.await(observeNodes("isolated mining complete", minerAIsolated, minerBIsolated))

      docker.stopNode(minerAIsolated.containerId)
      docker.stopNode(minerBIsolated.containerId)

      log.info("heightA: " + minerABestHeight)
      log.info("heightB: " + minerBBestHeight)

      (minerABestHeight > minerBBestHeight) shouldBe true

      // 3. Restart nodeA and nodeB (having shorter chain) with disabled mining
      val minerA: Node = docker.startDevNetNode(minerAConfigNonGen,
        specialVolumeOpt = Some((localVolumeA, remoteVolumeA))).get

      val minerB: Node = docker.startDevNetNode(minerBConfigNonGen,
        specialVolumeOpt = Some((localVolumeB, remoteVolumeB))).get
      Async.await(observeNodes("restarted without mining", minerA, minerB))

      val isMiningAOpt = Async.await(minerA.info).isMining
      log.info("isminingA: " + isMiningAOpt)
      isMiningAOpt map (_ shouldBe false)

      val isMiningBOpt = Async.await(minerB.info).isMining
      log.info("isminingB: " + isMiningBOpt)
      isMiningBOpt map (_ shouldBe false)

      // 5. Wait until it switches to the better chain
      val (minerAInfo, minerBInfo) =
        Async.await(waitForSameBestBlock(minerA, minerB, minerABestHeight, 10.minutes))

      log.info("Chain switching done")

      minerBInfo.bestBlockIdOpt shouldEqual minerAInfo.bestBlockIdOpt
    }

    try {
      Await.result(result, 20.minutes)
    } catch {
      case error: TimeoutException =>
        log.error(s"Deep rollback timed out; last observation: $lastObservation")
        throw error
    } finally {
      watches.foreach(_.close())
      observations.close()
    }
  }

}

object DeepRollBackSpec {
  // The retained peer database survives restarts. Disable automatic outgoing connections
  // and incoming admission during mining; final restarts use the ordinary node configs.
  private[it] val isolatedMiningConfig: Config = ConfigFactory.parseString("scorex.network.maxConnections = 0")
}
