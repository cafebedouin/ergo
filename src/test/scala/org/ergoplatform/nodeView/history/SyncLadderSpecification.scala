package org.ergoplatform.nodeView.history

import org.ergoplatform.consensus.Younger
import org.ergoplatform.modifiers.history.HeaderChain
import org.ergoplatform.nodeView.history.ErgoHistoryReader.FullV2SyncOffsets
import org.ergoplatform.nodeView.history.ErgoHistoryUtils._
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.utils.{ErgoCorePropertyTest, NoShrink}

/**
  * Two nodes share only genesis and then build their own chains; the shorter one is more than 16 blocks deep and
  * below height 128, so no offset of its full V2 sync summary lands on the shared prefix.
  */
class SyncLadderSpecification extends ErgoCorePropertyTest with NoShrink {
  import org.ergoplatform.utils.HistoryTestHelpers._
  import org.ergoplatform.utils.generators.ChainGenerator._

  private def genHistory(): ErgoHistory = {
    val h = generateHistory(verifyTransactions = true, StateType.Digest, PoPoWBootstrap = false, blocksToKeep = -1) // archival
    h.writeMinimalFullBlockHeight(GenesisHeight)
    h.isHeadersChainSyncedVar = true
    h
  }

  property("a node answers a deep-fork peer's full summary with a continuation from the shared prefix") {
    var a = genHistory()
    var b = genHistory()
    val genesis = genChain(1, a)
    a = applyChain(a, genesis)
    b = applyChain(b, genesis)
    val chainA = genChain(60, genesis.last).tail // heights 2..61
    Thread.sleep(2)
    val chainB = genChain(40, genesis.last).tail // heights 2..41, B's own fork
    a = applyChain(a, chainA)
    b = applyChain(b, chainB)

    val summaryB = b.syncInfoV2(full = true)
    a.compareV2(summaryB) shouldBe Younger
    val ext = a.continuationIdsV2(summaryB, size = 400)
    withClue(s"B's summary heights ${summaryB.lastHeaders.map(_.height)}; continuation length ${ext.length}: ") {
      ext.nonEmpty shouldBe true
    }
    ext.head._2 shouldBe chainA.head.id
  }

  property("a node far behind a heavier chain downloads that chain's blocks below its own full height") {
    var c = genHistory()
    val genesis = genChain(1, c)
    c = applyChain(c, genesis)
    val own = genChain(40, genesis.last).tail // C's own full chain, heights 2..41
    c = applyChain(c, own)
    Thread.sleep(2)
    val heavier = genChain(200, genesis.last).tail // heights 2..201, headers only
    c = applyHeaderChain(c, HeaderChain(heavier.map(_.header)))
    c.bestHeaderOpt.get shouldBe heavier.last.header
    c.bestFullBlockOpt.get.header shouldBe own.last.header

    val toDownload = c.nextModifiersToDownload(1000, (_, id) => !c.contains(id)).values.flatten.toSet
    val forkBase = heavier.head.blockSections.map(_.id).toSet // the heavier chain's block at height 2
    withClue(s"requested ${toDownload.size} section ids; fork base sections requested: ${(forkBase & toDownload).size}: ") {
      (forkBase & toDownload).nonEmpty shouldBe true
    }
  }

  property("a chain taller than the deepest offset sends only its offset samples in a full summary") {
    var d = genHistory()
    d = applyHeaderChain(d, genHeaderChain(600, d, diffBitsOpt = None, useRealTs = false))
    val h = d.headersHeight
    h should be > FullV2SyncOffsets.max
    d.syncInfoV2(full = true).lastHeaders.map(_.height) shouldBe FullV2SyncOffsets.toSeq.map(h - _)
  }

  property("a node close behind a heavier chain that forked more than 100 blocks below it downloads the fork's blocks") {
    var c = genHistory()
    val common = genChain(100, c) // heights 1..100
    c = applyChain(c, common)
    val own = genChain(151, common.last).tail // C's own full chain, heights 101..251
    c = applyChain(c, own)
    Thread.sleep(2)
    val heavier = genChain(201, common.last).tail // heights 101..301, headers only: fork 151 deep, lead 50
    c = applyHeaderChain(c, HeaderChain(heavier.map(_.header)))
    c.bestHeaderOpt.get shouldBe heavier.last.header
    c.bestFullBlockOpt.get.header shouldBe own.last.header

    val toDownload = c.nextModifiersToDownload(1000, (_, id) => !c.contains(id)).values.flatten.toSet
    val forkBase = heavier.head.blockSections.map(_.id).toSet // the heavier chain's block at height 101 (near-tip scheduling starts at 251 - 100)
    val requestedHeights = heavier.filter(_.blockSections.exists(s => toDownload.contains(s.id))).map(_.header.height)
    withClue(s"requested heavier-chain heights ${requestedHeights.headOption}..${requestedHeights.lastOption}: ") {
      (forkBase & toDownload).nonEmpty shouldBe true
    }
  }

  property("once the heavier chain leads by more than 128, a node downloads its blocks below the near-tip lookback") {
    var c = genHistory()
    val common = genChain(100, c) // heights 1..100
    c = applyChain(c, common)
    val own = genChain(151, common.last).tail // C's own full chain, heights 101..251
    c = applyChain(c, own)
    Thread.sleep(2)
    val heavier = genChain(281, common.last).tail // heights 101..381, headers only: fork 151 deep, lead 130
    c = applyHeaderChain(c, HeaderChain(heavier.map(_.header)))
    c.bestFullBlockOpt.get.header shouldBe own.last.header

    val toDownload = c.nextModifiersToDownload(1000, (_, id) => !c.contains(id)).values.flatten.toSet
    val forkBase = heavier.head.blockSections.map(_.id).toSet // the heavier chain's block at height 101
    val requestedHeights = heavier.filter(_.blockSections.exists(s => toDownload.contains(s.id))).map(_.header.height)
    withClue(s"requested heavier-chain heights ${requestedHeights.headOption}..${requestedHeights.lastOption}: ") {
      (forkBase & toDownload).nonEmpty shouldBe true
    }
  }
}
