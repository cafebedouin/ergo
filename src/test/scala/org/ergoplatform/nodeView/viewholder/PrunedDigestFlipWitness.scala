package org.ergoplatform.nodeView.viewholder

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import java.io.File
import java.nio.file.Files
import org.ergoplatform.mining.DefaultFakePowScheme
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.nodeView.ErgoNodeViewRef
import org.ergoplatform.nodeView.state.{ErgoState, StateType}
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.{ErgoSettings, ErgoSettingsReader}
import org.ergoplatform.utils.{ErgoCorePropertyTest, NodeViewTestContext, NodeViewTestOps}
import org.ergoplatform.utils.ErgoCoreTestConstants.parameters
import org.ergoplatform.utils.generators.ValidBlocksGenerators.validFullBlock
import org.ergoplatform.wallet.utils.FileUtils
import scorex.db.{LDBFactory, StoreRegistry}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/** Local witness: a pruned Digest node (no snapshot ever applied) restarted with utxoBootstrap = true. */
class PrunedDigestFlipWitness extends ErgoCorePropertyTest with NodeViewTestOps with FileUtils {

  private def parsedSettings(directory: File, utxoBootstrap: Boolean): ErgoSettings = {
    Files.createDirectories(directory.toPath.resolve("wallet/keystore"))
    val config = ConfigFactory.parseString(
      s"""ergo.node.stateType = "digest"
         |ergo.node.blocksToKeep = 10
         |ergo.node.utxo.utxoBootstrap = $utxoBootstrap
         |ergo.node.nipopow.nipopowBootstrap = false
         |ergo.node.verifyTransactions = true
         |ergo.node.mining = false
         |ergo.node.extraIndex = false
         |ergo.chain.voting.votingLength = 20
         |""".stripMargin)
      .withValue("ergo.directory", ConfigValueFactory.fromAnyRef(directory.getAbsolutePath))
      .withValue("ergo.wallet.secretStorage.secretDir",
        ConfigValueFactory.fromAnyRef(new File(directory, "wallet/keystore").getAbsolutePath))
      .withFallback(ConfigFactory.load())
      .resolve()
    val parsed = ErgoSettingsReader.fromConfig(config)
    parsed.nodeSettings.stateType shouldBe StateType.Digest
    parsed.nodeSettings.blocksToKeep shouldBe 10
    parsed.nodeSettings.utxoSettings.utxoBootstrap shouldBe utxoBootstrap
    parsed.copy(chainSettings = parsed.chainSettings.copy(
      powScheme = new DefaultFakePowScheme(parsed.chainSettings.powScheme.k, parsed.chainSettings.powScheme.n)))
  }

  private def closeOwnedStores(directory: File): Unit = {
    val root = directory.getCanonicalFile.toPath
    val registry = LDBFactory.factory.asInstanceOf[StoreRegistry]
    registry.lock.writeLock().lock()
    try {
      registry.map.toVector.collect {
        case (path, db) if path.getCanonicalFile.toPath.startsWith(root) => db
      }.foreach(_.close())
    } finally registry.lock.writeLock().unlock()
  }

  private class Session(override val settings: ErgoSettings) extends NodeViewTestContext {
    override val actorSystem: ActorSystem = ActorSystem("flip", ConfigFactory.parseString(
      """akka.coordinated-shutdown.terminate-actor-system = on
        |akka.coordinated-shutdown.exit-jvm = off
        |""".stripMargin).withFallback(ConfigFactory.load()))
    override val testProbe: TestProbe = TestProbe()(actorSystem)
    override val nodeViewHolderRef: ActorRef = ErgoNodeViewRef(settings)(actorSystem)

    def stop(): Unit = {
      Await.result(actorSystem.terminate(), 30.seconds)
      closeOwnedStores(new File(settings.directory))
    }
  }

  property("pruned Digest store restarted with utxoBootstrap = true") {
    val root = Files.createTempDirectory("pruned-digest-flip-").toFile
    val nodeDir = new File(root, "node")
    val pruned = parsedSettings(nodeDir, utxoBootstrap = false)
    val flipped = parsedSettings(nodeDir, utxoBootstrap = true)
    val sourceSettings = parsedSettings(new File(root, "source"), utxoBootstrap = false)

    val sourceDir = new File(sourceSettings.directory, "state")
    Files.createDirectories(sourceDir.toPath)
    val (genesis, boxes) = ErgoState.generateGenesisUtxoState(sourceDir, sourceSettings, Some(parameters))
    var source = WrappedUtxoState(genesis, boxes, sourceSettings)
    var parent: Option[ErgoFullBlock] = None
    val startTime = System.currentTimeMillis() - 46000L
    val blocks = (1 to 45).map { height =>
      val block = validFullBlock(parent, source, startTime + height * 1000L)
      source = source.applyModifier(block)(_ => ()).get
      parent = Some(block)
      block
    }

    // 1. ordinary pruned Digest node syncs headers; no snapshot, no full block
    val first = new Session(pruned)
    blocks.foreach(block => applyHeader(block.header)(first).get)
    val h1 = getHistory(first)
    info(s"after header sync: floor=${h1.minimalFullBlockHeight}, headersSynced=${h1.isHeadersChainSynced}, " +
      s"bestFullBlock=${h1.bestFullBlockOpt.map(_.height)}")
    if (h1.minimalFullBlockHeight == 1) {
      info("toDownload did not advance the floor in this fixture; calling updateBestFullBlock directly")
      h1.updateBestFullBlock(blocks.last.header)
    }
    val floor = getHistory(first).minimalFullBlockHeight
    info(s"pruning floor written: $floor; isUtxoSnapshotApplied=${getHistory(first).isUtxoSnapshotApplied}")
    floor should be > 1
    getHistory(first).bestFullBlockOpt shouldBe None
    first.stop()

    // 2. control: same config restarts
    val control = new Session(pruned)
    val controlStarted = Try(getHistory(control).minimalFullBlockHeight)
    info(s"CONTROL restart, utxoBootstrap=false: $controlStarted")
    control.stop()
    controlStarted.toOption shouldBe Some(floor)

    // 3. flip: utxoBootstrap = true on the same store
    val flip = new Session(flipped)
    val terminated = Try(Await.result(flip.actorSystem.whenTerminated, 25.seconds)).isSuccess
    val flipStarted = if (terminated) None else Try(getHistory(flip).minimalFullBlockHeight).toOption
    info(s"FLIP restart, utxoBootstrap=true: actorSystemTerminated=$terminated, view=$flipStarted")
    Try(flip.stop())
  }
}
