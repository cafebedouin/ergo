package org.ergoplatform.nodeView.viewholder

import java.net.InetSocketAddress

import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.history.extension.{Extension, ExtensionCandidate}
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages._
import org.ergoplatform.network.message.inputblocks.{InputBlockTransactionsData, OrderingBlockAnnouncement}
import org.ergoplatform.nodeView.ErgoNodeViewHolder.{DownloadInputBlock, DownloadRequest}
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.subblocks.InputBlockUncles
import org.ergoplatform.utils.{ErgoCorePropertyTest, InputBlockUnclesTestHelpers, NodeViewTestConfig, NodeViewTestOps}
import org.ergoplatform.utils.fixtures.NodeViewFixture
import scorex.core.network.{ConnectedPeer, ConnectionId, Outgoing}
import scorex.util.{bytesToId, idToBytes}

import scala.concurrent.duration._

/**
  * Node view holder with input-block uncles (node setting `inputBlockUncles`): unknown uncles are requested, and
  * an ordering block's transactions are rebuilt from L of the input block it links plus the uncles it references,
  * followed by its own transactions.
  */
class ErgoNodeViewHolderUnclesSpec extends ErgoCorePropertyTest with NodeViewTestOps with InputBlockUnclesTestHelpers {

  import org.ergoplatform.utils.ErgoCoreTestConstants.parameters
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  private def nodeSettings(uncles: Boolean): ErgoSettings = {
    val s = NodeViewTestConfig(StateType.Utxo, verifyTransactions = true, popowBootstrap = false).toSettings
    s.copy(nodeSettings = s.nodeSettings.copy(inputBlockUncles = uncles))
  }

  private def peer(fixture: NodeViewFixture): ConnectedPeer = ConnectedPeer(
    ConnectionId(new InetSocketAddress("127.0.0.1", 1234), new InetSocketAddress("127.0.0.1", 5678), Outgoing),
    fixture.testProbe.ref,
    None
  )

  property("unknown uncle of an input block is requested") {
    new NodeViewFixture(nodeSettings(uncles = true), parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(parentOpt = None, us, bh)
      applyBlock(genesis).isSuccess shouldBe true
      val wus = WrappedUtxoState(us, bh, fixture.settings).applyModifier(genesis)(_ => ()).get

      val unknown = bytesToId(Array.fill(32)(5.toByte))
      val ib = announceOn(validFullBlock(Some(genesis), wus).header, None, Seq.empty, uncles = Seq(unknown))

      subscribeEvents(classOf[DownloadInputBlock])
      nodeViewHolderRef ! ProcessInputBlock(ib, peer(fixture))
      testProbe.fishForMessage(5.seconds) {
        case DownloadInputBlock(id, _) => id == unknown
        case _ => false
      }
    }
  }

  /**
    * Input blocks A (first transaction of a valid block) and S (second one) are both children of the ordering
    * block; the next ordering block links A, merges S as an uncle and has the rest of the transactions.
    */
  private def orderingBlockOverInputBlocks(uncles: Boolean)(expectRebuilt: (NodeViewFixture, OrderingBlockAnnouncement) => Unit): Unit = {
    new NodeViewFixture(nodeSettings(uncles), parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(parentOpt = None, us, bh)
      applyBlock(genesis).isSuccess shouldBe true
      val wus = WrappedUtxoState(us, bh, fixture.settings).applyModifier(genesis)(_ => ()).get

      val nextBlock = validFullBlock(Some(genesis), wus)
      val txs = nextBlock.blockTransactions.txs
      assume(txs.size >= 2)

      val a = announceOn(validFullBlock(Some(genesis), wus).header, None, txs.take(1))
      val s = announceOn(validFullBlock(Some(genesis), wus).header, None, txs.slice(1, 2))
      Seq(a -> txs.take(1), s -> txs.slice(1, 2)).foreach { case (ib, ibTxs) =>
        nodeViewHolderRef ! ProcessInputBlock(ib, peer(fixture))
        nodeViewHolderRef ! ProcessInputBlockTransactions(InputBlockTransactionsData(ib.id, ibTxs))
      }
      Thread.sleep(1000)
      getHistory.getInputBlockTransactions(s.id).isDefined shouldBe true

      val fields = nextBlock.extension.fields ++ Seq(
        Extension.PrevInputBlockIdKey -> idToBytes(a.id),
        Extension.InputBlockUnclesKey -> InputBlockUncles.fieldValue(Seq(idToBytes(s.id))))
      val header = nextBlock.header.copy(extensionRoot = ExtensionCandidate(fields).digest)
      val oba = OrderingBlockAnnouncement(OrderingBlockAnnouncement.CurrentVersion, header, txs.drop(2), Seq.empty, fields)

      subscribeEvents(classOf[SyntacticallySuccessfulModifier])
      subscribeEvents(classOf[DownloadRequest])
      nodeViewHolderRef ! ProcessOrderingBlock(oba)
      expectRebuilt(fixture, oba)
    }
  }

  property("ordering block transactions rebuilt from L and uncles, not downloaded") {
    orderingBlockOverInputBlocks(uncles = true) { (fixture, oba) =>
      fixture.testProbe.fishForMessage(10.seconds) {
        case DownloadRequest(toFetch) if toFetch.contains(BlockTransactions.modifierTypeId) =>
          fail("block transactions downloaded instead of rebuilt from input blocks")
        case SyntacticallySuccessfulModifier(_, id) => id == oba.header.transactionsId
        case _ => false
      }
    }
  }

  property("uncles disabled: ordering block transactions are downloaded as before") {
    orderingBlockOverInputBlocks(uncles = false) { (fixture, oba) =>
      fixture.testProbe.fishForMessage(10.seconds) {
        case DownloadRequest(toFetch) => toFetch.get(BlockTransactions.modifierTypeId).contains(Seq(oba.header.transactionsId))
        case _ => false
      }
    }
  }

}
