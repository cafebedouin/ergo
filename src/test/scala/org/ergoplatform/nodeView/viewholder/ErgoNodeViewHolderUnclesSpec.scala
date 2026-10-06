package org.ergoplatform.nodeView.viewholder

import java.net.InetSocketAddress

import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.history.extension.{Extension, ExtensionCandidate}
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages._
import org.ergoplatform.network.message.inputblocks.{InputBlockTransactionsData, OrderingBlockAnnouncement}
import org.ergoplatform.nodeView.ErgoNodeViewHolder.{DownloadInputBlock, DownloadRequest}
import org.ergoplatform.nodeView.state.{ErgoState, StateType}
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.Constants.TrueTree
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

  /** Polls until the condition holds or the timeout passes. */
  private def awaitCondition(timeout: FiniteDuration)(condition: => Boolean): Boolean = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    while (!condition && System.currentTimeMillis() < deadline) Thread.sleep(100)
    condition
  }

  /**
    * After the genesis block, input blocks of the next ordering block built from ordinary transactions
    * (anyone-can-spend boxes, outputs at the creation height of the spent box, no double spends):
    * A splits a genesis output into two, B (child of A) spends one half, S (sibling of B) spends the other.
    * The next ordering block links B and merges S as an uncle, with no transactions of its own, so its
    * transactions are L(B) ++ S = A, B, S. First asserts that the input blocks were applied, so a fixture
    * problem can not read as a reconstruction failure.
    */
  private def orderingBlockOverInputBlocks(uncles: Boolean)(expectRebuilt: (NodeViewFixture, OrderingBlockAnnouncement) => Unit): Unit = {
    new NodeViewFixture(nodeSettings(uncles), parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(parentOpt = None, us, bh)
      applyBlock(genesis).isSuccess shouldBe true
      val wus = WrappedUtxoState(us, bh, fixture.settings).applyModifier(genesis)(_ => ()).get

      val trueBoxes = ErgoState.newBoxes(genesis.transactions).filter(_.ergoTree == TrueTree)
      trueBoxes.nonEmpty shouldBe true
      val box = trueBoxes.maxBy(_.value)
      val aTx = split(box)
      val bTx = spend(aTx.outputs(0))
      val sTx = spend(aTx.outputs(1))

      val a = announceOn(validFullBlock(Some(genesis), wus).header, None, Seq(aTx))
      val b = announceOn(validFullBlock(Some(genesis), wus).header, Some(a.id), Seq(bTx))
      val s = announceOn(validFullBlock(Some(genesis), wus).header, Some(a.id), Seq(sTx))
      Seq(a -> aTx, b -> bTx, s -> sTx).foreach { case (ib, tx) =>
        nodeViewHolderRef ! ProcessInputBlock(ib, peer(fixture))
        nodeViewHolderRef ! ProcessInputBlockTransactions(InputBlockTransactionsData(ib.id, Seq(tx)))
      }

      // the input blocks were applied: A <- B is the best input chain, S is stored (and, with uncles enabled,
      // validated as a sibling)
      awaitCondition(10.seconds) {
        getHistory.bestInputBlocksChain() == Seq(b.id, a.id) && getHistory.getInputBlockTransactions(s.id).isDefined &&
          (!uncles || getHistory.getInputBlockValidity(s.id).contains(true))
      } shouldBe true
      if (uncles) {
        getHistory.getInputBlockValidity(a.id) shouldBe Some(true)
        getHistory.getInputBlockValidity(b.id) shouldBe Some(true)
        getHistory.mergeableUncleCandidates() shouldBe Seq(s.id)
      }

      // the next ordering block, with the transactions A, B, S in the collected order
      val nextBlock = validFullBlock(Some(genesis), wus, Seq(aTx, bTx, sTx))
      val fields = nextBlock.extension.fields ++ Seq(
        Extension.PrevInputBlockIdKey -> idToBytes(b.id),
        Extension.InputBlockUnclesKey -> InputBlockUncles.fieldValue(Seq(idToBytes(s.id))))
      val header = nextBlock.header.copy(extensionRoot = ExtensionCandidate(fields).digest)
      if (uncles) {
        // the receiver's rebuild (the function the node view holder uses), checked against the header's root;
        // on a mismatch its Left message lists the rebuilt ids
        val expectedIds = Seq(aTx, bTx, sTx).map(_.id)
        getHistory.orderingBlockCollectedTransactions(genesis.id, fields).map(_.map(_.id)) shouldBe Some(expectedIds)
        getHistory.rebuildOrderingBlockTransactions(header, fields, Seq.empty).map(_.map(_.id)) shouldBe Right(expectedIds)
      }
      val oba = OrderingBlockAnnouncement(OrderingBlockAnnouncement.CurrentVersion, header, Seq.empty, Seq.empty, fields)

      subscribeEvents(classOf[SyntacticallySuccessfulModifier])
      subscribeEvents(classOf[DownloadRequest])
      nodeViewHolderRef ! ProcessOrderingBlock(oba)
      expectRebuilt(fixture, oba)
    }
  }

  // Appending the ordering block's header makes the node request its missing sections (a DownloadRequest for the
  // block transactions among them) whatever the reconstruction does, so download requests do not tell the outcome.
  // The block transactions are applied only if they were rebuilt: nothing else supplies them in these tests.

  property("ordering block transactions rebuilt from L and uncles, not downloaded") {
    orderingBlockOverInputBlocks(uncles = true) { (fixture, oba) =>
      fixture.testProbe.fishForMessage(10.seconds) {
        case SyntacticallySuccessfulModifier(_, id) => id == oba.header.transactionsId
        case _ => false
      }
    }
  }

  property("uncles disabled: ordering block transactions are not rebuilt, they are downloaded as before") {
    orderingBlockOverInputBlocks(uncles = false) { (fixture, oba) =>
      import fixture._
      testProbe.fishForMessage(10.seconds) {
        case DownloadRequest(toFetch) => toFetch.get(BlockTransactions.modifierTypeId).contains(Seq(oba.header.transactionsId))
        case _ => false
      }
      Thread.sleep(1000)
      getHistory.contains(oba.header.transactionsId) shouldBe false
    }
  }

}
