package org.ergoplatform.mining

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.StatusReply
import akka.testkit.{TestKit, TestProbe}
import org.ergoplatform.mining.CandidateGenerator.{Candidate, GenerateCandidate}
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.FullBlockApplied
import org.ergoplatform.nodeView.{ErgoNodeViewRef, ErgoReadersHolderRef, LocallyGeneratedModifier}
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.settings.{ErgoSettings, ErgoSettingsReader}
import org.ergoplatform.utils.ErgoTestHelpers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
  * A miner mines its own block at the height after genesis while its best header there (or beyond) belongs to a peer
  * whose block bodies never arrive. The miner must get its own block applied and keep mining.
  */
class MinerProgressBehindBodilessHeaderSpec extends AnyFlatSpec with Matchers with ErgoTestHelpers {
  import org.ergoplatform.utils.ErgoCoreTestConstants._

  private def settingsIn(dir: String): ErgoSettings = {
    val empty = ErgoSettingsReader.read()
    val nodeSettings = empty.nodeSettings.copy(mining = true, stateType = StateType.Utxo,
      internalMinerPollingInterval = 1.second, offlineGeneration = true, verifyTransactions = true)
    empty.copy(nodeSettings = nodeSettings, chainSettings = empty.chainSettings.copy(blockInterval = 1.second),
      directory = dir)
  }

  private final class Node(system: ActorSystem, name: String) {
    val settings: ErgoSettings = settingsIn(java.nio.file.Files.createTempDirectory(s"own-block-$name").toFile.getAbsolutePath)
    val viewHolder: ActorRef = ErgoNodeViewRef(settings)(system)
    val generator: ActorRef = CandidateGenerator(defaultMinerSecret.publicImage,
      ErgoReadersHolderRef(viewHolder)(system), viewHolder, settings)(system)
    val applied = new TestProbe(system)
    system.eventStream.subscribe(applied.ref, classOf[FullBlockApplied])
    val reply = new TestProbe(system)

    /** asks for a candidate and solves it with a nonce search starting at `nonceFrom` */
    def solve(nonceFrom: Long): ErgoFullBlock = {
      generator.tell(GenerateCandidate(Seq.empty, reply = true, forced = false), reply.ref)
      reply.expectMsgPF(10.seconds) {
        case StatusReply.Success(c: Candidate) =>
          settings.chainSettings.powScheme.proveCandidate(c.candidateBlock, defaultMinerSecret.w, nonceFrom, nonceFrom + 100000).get
      }
    }
    /** submits the solution of `block` (built from this node's own cached candidate) */
    def submit(block: ErgoFullBlock): Any = {
      generator.tell(block.header.powSolution, reply.ref)
      reply.expectMsgType[StatusReply[_]](10.seconds)
    }
    def feed(block: ErgoFullBlock, headerOnly: Boolean): Unit = {
      viewHolder ! LocallyGeneratedModifier(block.header)
      if (!headerOnly) block.mandatoryBlockSections.foreach(s => viewHolder ! LocallyGeneratedModifier(s))
    }
  }

  private def scenario(peerBlocks: Int): Unit = new TestKit(ActorSystem()) {
    val peerSystem = ActorSystem()
    try {
      val c = new Node(system, "c")
      val peer = new Node(peerSystem, "peer")

      // shared genesis: mined by c, given in full to the peer
      val genesis = c.solve(0L)
      c.submit(genesis)
      c.applied.expectMsgType[FullBlockApplied](20.seconds)
      peer.feed(genesis, headerOnly = false)
      peer.applied.expectMsgType[FullBlockApplied](20.seconds)

      // the peer mines its own chain; c receives only the headers (the bodies never arrive)
      val peerChain = (1 to peerBlocks).map { _ =>
        val b = peer.solve(0L)
        peer.submit(b)
        peer.applied.expectMsgType[FullBlockApplied](20.seconds)
        b
      }
      peerChain.foreach(b => c.feed(b, headerOnly = true))
      Thread.sleep(1000)

      // c mines its own block at the height after genesis, from a different nonce range than the peer
      val own = c.solve(5000000L)
      own.header.height shouldBe genesis.header.height + 1
      own.id should not be peerChain.head.id
      c.submit(own)
      val ownApplied = c.applied.receiveWhile(8.seconds) { case a: FullBlockApplied => a.header.id }.contains(own.id)

      // can c mine the next block? (a new solution is accepted and applied)
      val resumed = ownApplied && {
        val next = c.solve(9000000L)
        c.submit(next) match {
          case r: StatusReply[_] if r.isSuccess =>
            c.applied.receiveWhile(8.seconds) { case a: FullBlockApplied => a.header.id }.contains(next.id)
          case _ => false
        }
      }
      withClue(s"own block applied: $ownApplied; ") { resumed shouldBe true }
    } finally {
      TestKit.shutdownActorSystem(peerSystem)
      TestKit.shutdownActorSystem(system)
    }
  }

  it should "keep mining when a peer's bodiless header ties its own block (1 header)" in {
    scenario(1)
  }

  it should "keep mining when a peer's bodiless header chain is heavier than its own block (2 headers)" in {
    scenario(2)
  }
}
