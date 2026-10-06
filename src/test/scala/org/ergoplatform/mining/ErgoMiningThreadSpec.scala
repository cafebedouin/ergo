package org.ergoplatform.mining

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.StatusReply
import akka.testkit.{TestKit, TestProbe}
import org.ergoplatform.mining.CandidateGenerator.{Candidate, GenerateCandidate}
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.nodeView.{ErgoNodeViewRef, ErgoReadersHolderRef}
import org.ergoplatform.settings.{ErgoSettings, ErgoSettingsReader}
import org.ergoplatform.utils.ErgoTestHelpers
import com.google.common.primitives.Longs
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.settings.Parameters
import org.ergoplatform.{AutolykosSolution, InputBlockHeaderFound, InputSolutionFound, NothingFound,
  OrderingBlockHeaderFound, OrderingSolutionFound, ProveBlockResult, SolutionFound}
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.Digest32
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ErgoMiningThreadSpec extends AnyFlatSpec with Matchers with ErgoTestHelpers {
  import org.ergoplatform.utils.ErgoCoreTestConstants._

  private val settings: ErgoSettings = {
    val empty = ErgoSettingsReader.read()
    empty.copy(
      nodeSettings = empty.nodeSettings.copy(
        mining = true,
        stateType = StateType.Utxo,
        internalMinerPollingInterval = 1.second,
        offlineGeneration = true,
        verifyTransactions = true
      ),
      chainSettings = empty.chainSettings.copy(blockInterval = 1.seconds)
    )
  }

  /** Fake PoW with input-block hits at fixed nonces only, so the miner's nonce search is observable. */
  private class FixedHitsPowScheme(hits: Seq[Long]) extends DefaultFakePowScheme(32, 26) {
    override def prove(parentOpt: Option[Header],
                       version: Header.Version,
                       nBits: Long,
                       stateRoot: ADDigest,
                       adProofsRoot: Digest32,
                       transactionsRoot: Digest32,
                       timestamp: Header.Timestamp,
                       extensionHash: Digest32,
                       votes: Array[Byte],
                       sk: PrivateKey,
                       minNonce: Long,
                       maxNonce: Long,
                       parameters: Parameters): ProveBlockResult =
      hits.find(h => h >= minNonce && h < maxNonce) match {
        case None => NothingFound
        case Some(hit) =>
          super.prove(parentOpt, version, nBits, stateRoot, adProofsRoot, transactionsRoot, timestamp, extensionHash,
            votes, sk, minNonce, maxNonce, parameters) match {
            case OrderingBlockHeaderFound(h) =>
              val s = h.powSolution
              InputBlockHeaderFound(h.copy(powSolution = new AutolykosSolution(s.pk, s.w, Longs.toByteArray(hit), s.d)))
            case other => other
          }
      }
  }

  it should "not resubmit a rejected solution's nonce for the same candidate" in new TestKit(ActorSystem()) {
    // a real candidate, from a real generator
    val viewHolderRef: ActorRef = ErgoNodeViewRef(settings)
    val readersHolderRef: ActorRef = ErgoReadersHolderRef(viewHolderRef)
    val realGenerator: ActorRef =
      CandidateGenerator(defaultMinerSecret.publicImage, readersHolderRef, viewHolderRef, settings)
    val candidateProbe = TestProbe()
    realGenerator.tell(GenerateCandidate(Seq.empty, reply = true, forced = false, optPk = None), candidateProbe.ref)
    val candidate = candidateProbe.expectMsgPF(5.seconds) { case StatusReply.Success(c: Candidate) => c }

    // the miner thread talks to a probe standing in for the generator
    val generator = TestProbe()
    // input-block hits at nonces 5 and 900 of the first 1000-nonce batch
    val minerSettings = settings.copy(chainSettings = settings.chainSettings.copy(powScheme = new FixedHitsPowScheme(Seq(5L, 900L))))
    val thread = ErgoMiningThread(minerSettings, generator.ref, defaultMinerSecret.w)
    generator.fishForMessage(5.seconds) { case _: GenerateCandidate => true; case _ => false } // a subscription may come first
    generator.reply(StatusReply.Success(candidate))

    def nextSolution(): SolutionFound = generator.fishForMessage(10.seconds) {
      case _: InputSolutionFound | _: OrderingSolutionFound => true
      case _ => false // periodic candidate polls
    }.asInstanceOf[SolutionFound]

    val first = nextSolution()
    // the generator rejects it (e.g. the candidate it was mined on is no longer the cached one)
    thread.tell(StatusReply.Error(new Exception("Invalid input block! PoW valid: false")), generator.ref)
    val second = nextSolution()

    val (n1, n2) = (Longs.fromByteArray(first.as.n), Longs.fromByteArray(second.as.n))
    println(s"X040 first nonce=$n1 second nonce=$n2")
    n1 shouldBe 5L
    n2 should not be n1
    system.terminate()
  }

  /** Fake PoW that finds nothing and takes `stepMillis` per step. */
  private class SlowNothingPowScheme(stepMillis: Long) extends DefaultFakePowScheme(32, 26) {
    override def prove(parentOpt: Option[Header], version: Header.Version, nBits: Long, stateRoot: ADDigest,
                       adProofsRoot: Digest32, transactionsRoot: Digest32, timestamp: Header.Timestamp,
                       extensionHash: Digest32, votes: Array[Byte], sk: PrivateKey, minNonce: Long, maxNonce: Long,
                       parameters: Parameters): ProveBlockResult = {
      Thread.sleep(stepMillis)
      NothingFound
    }
  }

  it should "run one nonce-search chain however many candidates arrive while mining" in new TestKit(ActorSystem()) {
    val viewHolderRef: ActorRef = ErgoNodeViewRef(settings)
    val readersHolderRef: ActorRef = ErgoReadersHolderRef(viewHolderRef)
    val realGenerator: ActorRef =
      CandidateGenerator(defaultMinerSecret.publicImage, readersHolderRef, viewHolderRef, settings)
    val candidateProbe = TestProbe()
    realGenerator.tell(GenerateCandidate(Seq.empty, reply = true, forced = false, optPk = None), candidateProbe.ref)
    val candidate = candidateProbe.expectMsgPF(30.seconds) { case StatusReply.Success(c: Candidate) => c }

    val stepMillis = 20L
    val generator = TestProbe()
    val minerSettings = settings.copy(chainSettings =
      settings.chainSettings.copy(powScheme = new SlowNothingPowScheme(stepMillis)))
    val thread = ErgoMiningThread(minerSettings, generator.ref, defaultMinerSecret.w)
    generator.fishForMessage(5.seconds) { case _: GenerateCandidate => true; case _ => false }
    generator.reply(StatusReply.Success(candidate))

    // 20 new candidates (distinct timestamps) while the thread is mining
    val n = 20
    (1 to n).foreach { i =>
      val cb = candidate.candidateBlock.copy(timestamp = candidate.candidateBlock.timestamp + i)
      thread ! StatusReply.Success(candidate.copy(candidateBlock = cb))
    }
    Thread.sleep(stepMillis * (n + 5))

    // with one chain in flight a message waits at most about one step; with n+1 chains it waits about n+1 steps
    val probe = TestProbe()
    val start = System.nanoTime()
    thread.tell(ErgoMiningThread.GetSolvedBlocksCount, probe.ref)
    probe.expectMsgClass(5.seconds, classOf[ErgoMiningThread.SolvedBlocksCount])
    val lagMillis = (System.nanoTime() - start) / 1000000
    info(s"mailbox lag after $n candidates: $lagMillis ms (step $stepMillis ms)")
    lagMillis should be < (stepMillis * 5)
    system.terminate()
  }
}
