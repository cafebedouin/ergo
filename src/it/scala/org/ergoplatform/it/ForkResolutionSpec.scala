package org.ergoplatform.it

import java.io.File
import cats.implicits._
import com.typesafe.config.Config
import org.ergoplatform.it.container.Docker.{ExtraConfig, noExtraConfig}
import org.ergoplatform.it.container.{IntegrationSuite, Node}
import org.ergoplatform.it.util.{ConvergenceObservations, ConvergenceWatch, NodeSample}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.async.Async
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

class ForkResolutionSpec extends AnyFlatSpec with Matchers with IntegrationSuite with Eventually {

  val nodesQty: Int = 4

  val commonChainLength: Int = 5
  val forkLength: Int = 5
  val syncLength: Int = 15

  protected def totalTimeout: FiniteDuration = 15.minutes

  val localVolumes: Seq[String] = (1 to nodesQty).map(localVolume)
  val remoteVolume = "/app"

  val volumesMapping: Seq[(String, String)] = localVolumes.map(_ -> remoteVolume)

  val dirs: Seq[File] = localVolumes.map(vol => new File(vol))
  dirs.foreach(_.mkdirs())

  val miningTimingConfig: Config = shortInternalMinerPollingInterval
    .withFallback(blockIntervalConfig(500))

  val nodeConfigs: List[Config] = nodeSeedConfigs.take(4)
    .map(_.withFallback(allowLocalConfig).withFallback(miningTimingConfig))

  val minerConfig: Config = nodeConfigs.head
  val onlineSyncNodesConfig: List[Config] = nodeConfigs.slice(1, nodesQty)
    .map(_.withFallback(nonGeneratingPeerConfig))
  val offlineMiningNodesConfig: List[Config] = nodeConfigs.slice(1, nodesQty)

  def localVolume(n: Int): String = s"$localDataDir/fork-resolution-spec/node-$n/data"

  def clearPeerDatabases(): Unit = {
    volumesMapping.foreach { case (localVolume, remoteVolume) =>
      docker.removeFromMountedVolume(localVolume, remoteVolume, "peers")
    }
  }

  def startNodesWithBinds(nodeConfigs: List[Config],
                          configEnrich: ExtraConfig = noExtraConfig): List[Node] = {
    log.trace(s"Starting ${nodeConfigs.size} containers")
    val nodes: Try[List[Node]] = nodeConfigs
      .map(_.withFallback(specialDataDirConfig(remoteVolume)))
      .zip(volumesMapping)
      .map { case (cfg, vol) => docker.startDevNetNode(cfg, configEnrich, Some(vol)) }
      .sequence
    implicit val patienceConfig: PatienceConfig = PatienceConfig((nodeConfigs.size * 2).seconds, 3.second)
    eventually {
      Await.result(Future.traverse(nodes.get)(_.waitForStartup), 180.seconds)
    }
  }

  // Testing scenario:
  // 1. Start up {nodesQty} nodes and let them mine common chain of length {initialCommonChainLength};
  // 2. Kill all nodes when they are done, make them offline generating, clear known peers and restart them;
  // 3. Let them mine another {forkLength} blocks offline in order to create {nodesQty} forks;
  // 4. Kill all nodes again and restart with `knownPeers` filled, wait another {syncLength} blocks;
  // 5. Check that nodes reached consensus on created forks;
  it should "Fork resolution after isolated mining" in {

    log.info(minerConfig.toString)
    onlineSyncNodesConfig.foreach(x => log.info(x.toString))

    val nodes: List[Node] = startNodesWithBinds(minerConfig +: onlineSyncNodesConfig)

    // One budget for the whole scenario (as before); each wait names its phase, so a timeout reports which
    // phase expired, a cause and every node's last state (ConvergenceDiagnosis) instead of a bare timeout.
    val deadline = totalTimeout.fromNow

    def allReach(nodes: List[Node], height: Int, phase: String, expectPeers: Boolean = true): Future[Seq[NodeSample]] = {
      val watch = new ConvergenceWatch(docker, nodes, expectPeers = expectPeers)
      watch.until(deadline, 1.second)(_.forall(_.fullHeight.exists(_ >= height)))(
        s"$phase: not every node reached full height $height before the ${totalTimeout} budget ran out"
      ).andThen { case _ => watch.close() }
    }

    def agreeAt(nodes: List[Node], height: Int, phase: String): Future[Seq[String]] = {
      val watch = new ConvergenceWatch(docker, nodes)
      @volatile var lastIds = Seq.empty[String]
      watch.untilWith(deadline, 1.second) { _ =>
        Future.traverse(nodes)(_.headerIdsByHeight(height))
          .map { ids => lastIds = ids.map(_.headOption.map(_.take(8)).getOrElse("-")); ids }
      }(ConvergenceObservations.selectedHeadersAgree)(
        s"$phase: nodes did not agree on the header at height $height before the ${totalTimeout} budget ran out; " +
          s"selected ids: ${nodes.map(_.nodeName).zip(lastIds).map { case (n, id) => s"$n=$id" }.mkString(", ")}"
      ).map(_.map(_.head)).andThen { case _ => watch.close() }
    }

    val result = Async.async {
      val initMaxHeight = Async.await(Future.traverse(nodes)(_.fullHeight).map(_.max))
      Async.await(allReach(nodes, initMaxHeight + commonChainLength, "Phase 1 (common chain)"))
      val isolatedNodes = Async.await {
        nodes.foreach(node => docker.stopNode(node.containerId))
        clearPeerDatabases()
        Future.successful(startNodesWithBinds(minerConfig +: offlineMiningNodesConfig, isolatedPeersConfig))
      }
      val forkHeight = initMaxHeight + commonChainLength + forkLength
      // nodes are isolated on purpose here, so 0 peers is not a cause
      Async.await(allReach(isolatedNodes, forkHeight, "Phase 2 (isolated mining)", expectPeers = false))
      val regularNodes = Async.await {
        isolatedNodes.foreach(node => docker.stopNode(node.containerId))
        clearPeerDatabases()
        Future.successful(startNodesWithBinds(minerConfig +: onlineSyncNodesConfig))
      }
      Async.await(allReach(regularNodes, forkHeight + syncLength, "Phase 3 (sync after reconnect)"))
      val headers = Async.await(agreeAt(regularNodes, forkHeight, "Phase 4 (agreement at the fork height)"))

      log.debug(s"Headers at height $forkHeight: ${headers.mkString(",")}")
      headers.distinct should have size 1
    }

    // a little past the shared deadline, so the phase message wins over a bare timeout
    Await.result(result, totalTimeout + 30.seconds)
  }

}
