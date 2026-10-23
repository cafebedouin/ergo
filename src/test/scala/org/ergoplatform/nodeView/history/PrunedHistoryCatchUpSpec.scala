package org.ergoplatform.nodeView.history

import org.ergoplatform.modifiers.{ErgoFullBlock, NonHeaderBlockSection}
import org.ergoplatform.modifiers.history.HeaderChain
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.utils.{ErgoCorePropertyTest, NoShrink}
import org.ergoplatform.utils.ErgoNodeTestConstants.initSettings
import org.ergoplatform.validation.{MalformedModifierError, RecoverableModifierError}
import org.ergoplatform.wallet.utils.FileUtils

/**
  * A pruning node that has been offline longer than its `blocksToKeep` window must still be able to
  * catch up. While the node is offline the network tip advances past it by more than `blocksToKeep`.
  * On restart the headers re-sync and `minimalFullBlockHeight` jumps to roughly `tip - blocksToKeep + 1`,
  * which is now far above the node's local `bestFullBlockHeight`. To advance its state the node has to
  * apply the block sections of the intermediate heights.
  *
  * These intermediate sections sit below `minimalFullBlockHeight`, so the `bsTooOld` rule rejects them.
  * If that rejection is fatal the sections are marked permanently invalid and the node can never recover:
  *
  *   MalformedModifierError: Block section should correspond to a block header that is not pruned yet.
  *
  * This spec pins down the two properties that keep such a node unstuck: a block that is a successor of
  * the best full block stays applicable even below the window, and a genuinely too-old section is only
  * rejected recoverably.
  */
class PrunedHistoryCatchUpSpec extends ErgoCorePropertyTest with NoShrink with FileUtils {
  import org.ergoplatform.utils.HistoryTestHelpers._
  import org.ergoplatform.utils.generators.ChainGenerator._

  private def prunedDigestHistory(): ErgoHistory =
    generateHistory(verifyTransactions = true, StateType.Digest, PoPoWBootstrap = false, BlocksToKeep)

  property("a catch-up block above the jumped minimalFullBlockHeight stays applicable") {
    val chainLen = 8
    val gap = 5

    // local chain: apply all but the last block, so bestFullBlock is one below the tip we still need
    val history0 = prunedDigestHistory()
    val chain = genChain(chainLen, history0)
    val history = applyChain(history0, chain.init)
    val nextBlock = chain.last // the very next block the node must apply to advance its state
    history.append(nextBlock.header).get._1

    history.bestFullBlockOpt.get.header.height shouldBe nextBlock.header.height - 1

    // simulate the long shutdown: the network tip moved far past the keep window, so on re-sync
    // minimalFullBlockHeight is now well above every block we still hold locally
    history.writeMinimalFullBlockHeight(nextBlock.header.height + gap)
    history.isHeadersChainSyncedVar = true

    // the next-needed block sits below minimalFullBlockHeight but is a successor of bestFullBlock,
    // so it must remain applicable - otherwise the pruning node can never catch up
    history.applicableTry(nextBlock.blockTransactions) shouldBe 'success
  }

  property("a genuinely too-old block section is rejected recoverably, not fatally") {
    val chainLen = 8
    val gap = 5

    // fresh pruning node (no full block applied yet): only the headers chain is known
    val history = prunedDigestHistory()
    val chain = genChain(chainLen, history)
    chain.foldLeft(history)((h, b) => h.append(b.header).get._1)

    history.writeMinimalFullBlockHeight(chain.last.header.height + gap)
    history.isHeadersChainSyncedVar = true

    val tooOld = chain.head // far below the keep window and not a successor of any best full block
    val result = history.applicableTry(tooOld.blockTransactions)

    result shouldBe 'failure
    // "too old" is a transient condition relative to sync progress, never an intrinsic defect:
    // the section must not be permanently blacklisted, or the node can never recover.
    result.failed.get should not be a[MalformedModifierError]
    result.failed.get shouldBe a[RecoverableModifierError]
  }

  /** A pruned digest node after a restart; `chain` is the network's chain, its last header is the restart's first. */
  private case class RestartedNode(history: ErgoHistory, chain: Seq[ErgoFullBlock], bestFullBlockHeight: Int)

  /**
    * The floor can also move above the best full block without a long shutdown (reported on #1159, 2026-10-02):
    * a node still applying full blocks after its first headers sync is restarted, and the first fresh header after
    * the restart declares the headers chain synced again. `updateBestFullBlock` recomputes the floor from that
    * header and rounds it down to a voting-epoch start. If `header.height - blocksToKeep + 1` has crossed an epoch
    * start since the first sync, the floor lands above the best full block, although the headers tip moved by
    * fewer than `blocksToKeep` blocks (here 10). The history is closed and reopened, as on a node restart.
    * The best full block is left `belowFloor` blocks below the new floor.
    */
  private def withFloorMovedPastBestFullBlockByRestart(blocksToKeep: Int, belowFloor: Int = 3)
                                                     (test: RestartedNode => Unit): Unit = {
    val votingLength = initSettings.chainSettings.voting.votingLength
    val firstFloor = votingLength - 7
    val bestFullBlockHeight = votingLength - belowFloor
    val firstSyncHeight = firstFloor + blocksToKeep - 1 // floor at the first sync: firstSyncHeight - blocksToKeep + 1
    val restartHeight = firstSyncHeight + 10 // restartHeight - blocksToKeep + 1 = votingLength + 3
    val historySettings = initSettings.copy(
      directory = createTempDir.getAbsolutePath,
      chainSettings = initSettings.chainSettings.copy(epochLength = 100000000),
      nodeSettings = initSettings.nodeSettings.copy(
        stateType = StateType.Digest,
        verifyTransactions = true,
        blocksToKeep = blocksToKeep,
        utxoSettings = initSettings.nodeSettings.utxoSettings.copy(utxoBootstrap = false),
        nipopowSettings = initSettings.nodeSettings.nipopowSettings.copy(nipopowBootstrap = false),
        extraIndex = false
      )
    )

    // headers only, then a restart: the next fresh header declares the first headers sync at firstSyncHeight
    val empty = ErgoHistory.readOrGenerate(historySettings)(null)
    val chain = genChain(restartHeight, empty)
    try {
      applyHeaderChain(empty, HeaderChain(chain.take(firstSyncHeight - 1).map(_.header)))
    } finally {
      empty.closeStorage()
    }

    val synced = ErgoHistory.readOrGenerate(historySettings)(null)
    try {
      applyHeaderChain(synced, HeaderChain(chain.slice(firstSyncHeight - 1, restartHeight - 1).map(_.header)))
      applyChain(synced, chain.slice(firstFloor - 1, bestFullBlockHeight))
      withClue(s"floor=${synced.minimalFullBlockHeight} tip=${synced.headersHeight}: ") {
        synced.minimalFullBlockHeight shouldBe firstFloor
        synced.bestFullBlockOpt.map(_.height) shouldBe Some(bestFullBlockHeight)
      }
    } finally {
      synced.closeStorage()
    }

    val restarted = ErgoHistory.readOrGenerate(historySettings)(null)
    try {
      restarted.isHeadersChainSynced shouldBe false
      restarted.append(chain.last.header).get
      withClue(s"floor=${restarted.minimalFullBlockHeight} tip=${restarted.headersHeight}: ") {
        restarted.isHeadersChainSynced shouldBe true
        restarted.headersHeight shouldBe restartHeight
        restarted.minimalFullBlockHeight shouldBe votingLength
        restarted.bestFullBlockOpt.map(_.height) shouldBe Some(bestFullBlockHeight)
      }
      test(RestartedNode(restarted, chain, bestFullBlockHeight))
    } finally {
      restarted.closeStorage()
    }
  }

  /**
    * Appends the sections of `blocks` one type at a time (every block's transactions, then extensions, then proofs),
    * as a node receives the answer to one request batch: one message per section type.
    */
  private def applySectionsByType(history: ErgoHistory, blocks: Seq[ErgoFullBlock]): Unit = {
    val sectionTypes: Seq[ErgoFullBlock => Option[NonHeaderBlockSection]] =
      Seq(b => Some(b.blockTransactions), b => Some(b.extension), b => b.adProofs)
    sectionTypes.foreach { section =>
      blocks.flatMap(b => section(b)).foreach { s =>
        if (!history.contains(s)) history.append(s).get
      }
    }
  }

  // The catch-up properties below apply the rest of the chain block by block (`applyChain`), except the last one.
  // Headers end 155 / 55 blocks above the best full block: `nextModifiersToDownload` takes its branch for a node far
  // from the tip / near the tip (the boundary is 128 headers).
  Seq(150, 50).foreach { blocksToKeep =>
    property(s"after a restart moves the floor above the best full block (blocksToKeep = $blocksToKeep), " +
      "the next block is applicable and the node catches up") {
      withFloorMovedPastBestFullBlockByRestart(blocksToKeep) { node =>
        val next = node.chain(node.bestFullBlockHeight)
        node.history.applicableTry(next.blockTransactions) shouldBe 'success
        applyChain(node.history, node.chain.drop(node.bestFullBlockHeight))
        node.history.bestFullBlockOpt.map(_.height) shouldBe Some(node.chain.size)
      }
    }
  }

  // Far from the tip the scan already starts at the successor of the best full block; near the tip it must too.
  property("after a restart moves the floor above the best full block (blocksToKeep = 50), " +
    "the next block is scheduled for download") {
    withFloorMovedPastBestFullBlockByRestart(blocksToKeep = 50) { node =>
      val next = node.chain(node.bestFullBlockHeight)
      val scheduled = node.history.nextModifiersToDownload(1000, (_, id) => !node.history.contains(id))
        .values.flatten.toSeq
      val nextSections = node.history.requiredModifiersForHeader(next.header).map { case (_, id) => id }
      scheduled should contain allElementsOf nextSections
    }
  }

  // With the best full block at floor - 2, the block at floor - 1 is the first one applied, and it is applied on its
  // own when its last section arrives before the last section of the block at the floor.
  property("after a restart leaves the best full block two below the floor (blocksToKeep = 150), " +
    "the node catches up when the sections arrive one type at a time") {
    withFloorMovedPastBestFullBlockByRestart(blocksToKeep = 150, belowFloor = 2) { node =>
      applySectionsByType(node.history, node.chain.drop(node.bestFullBlockHeight))
      node.history.bestFullBlockOpt.map(_.height) shouldBe Some(node.chain.size)
    }
  }

}
