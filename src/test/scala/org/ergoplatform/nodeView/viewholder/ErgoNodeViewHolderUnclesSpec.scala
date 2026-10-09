package org.ergoplatform.nodeView.viewholder

import java.net.InetSocketAddress

import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages._
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.nodeView.state.wrapped.WrappedUtxoState
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.{ErgoCorePropertyTest, InputBlockUnclesTestHelpers, NodeViewTestConfig, NodeViewTestOps}
import org.ergoplatform.utils.fixtures.NodeViewFixture
import scorex.core.network.{ConnectedPeer, ConnectionId, Outgoing}

import scala.concurrent.duration._

/**
  * Node view holder with header-level input-block uncles (node setting `inputBlockUncles`): the announcement of a
  * sibling (an input block whose parent already has a known child) is relayed, so that miners can reference it.
  * Only the announcement: nothing about its transactions is added.
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

  /**
    * After the genesis block, announcements of input blocks A, B (child of A) and S (child of A, a sibling of B)
    * of the next ordering block are sent to the node view holder, with no transactions.
    */
  private def siblingAnnounced(uncles: Boolean): Unit = {
    new NodeViewFixture(nodeSettings(uncles), parameters).apply { fixture =>
      import fixture._
      val (us, bh) = createUtxoState(fixture.settings)
      val genesis = validFullBlock(parentOpt = None, us, bh)
      applyBlock(genesis).isSuccess shouldBe true
      val wus = WrappedUtxoState(us, bh, fixture.settings).applyModifier(genesis)(_ => ()).get

      val a = announceOn(validFullBlock(Some(genesis), wus).header, None, Seq.empty)
      val b = announceOn(validFullBlock(Some(genesis), wus).header, Some(a.id), Seq.empty)
      val s = announceOn(validFullBlock(Some(genesis), wus).header, Some(a.id), Seq.empty)

      subscribeEvents(classOf[NewInputBlockSibling])
      nodeViewHolderRef ! ProcessInputBlock(a, peer(fixture))
      nodeViewHolderRef ! ProcessInputBlock(b, peer(fixture))
      nodeViewHolderRef ! ProcessInputBlock(s, peer(fixture))
      if (uncles) {
        testProbe.expectMsg(10.seconds, NewInputBlockSibling(s.id, local = false))
      }
      // A and B extend the chain: not relayed as siblings
      testProbe.expectNoMessage(1.second)
      getHistory.getInputBlock(s.id).isDefined shouldBe true
    }
  }

  property("sibling announcement is relayed with uncles enabled") {
    siblingAnnounced(uncles = true)
  }

  property("uncles disabled: no sibling relay (base behaviour)") {
    siblingAnnounced(uncles = false)
  }

}
