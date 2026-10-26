package org.ergoplatform.mining

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import org.bouncycastle.util.BigIntegers
import org.ergoplatform.mining.CandidateGenerator.{Candidate, GenerateCandidate}
import org.ergoplatform.mining.ErgoMiner.StartMining
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnsignedErgoTransaction}
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.FullBlockApplied
import org.ergoplatform.nodeView.ErgoReadersHolder.{GetReaders, Readers}
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.nodeView.{ErgoNodeViewRef, ErgoReadersHolderRef}
import org.ergoplatform.settings.{ErgoSettings, ErgoSettingsReader, NetworkType}
import org.ergoplatform.utils.ErgoTestHelpers
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, Input}
import org.scalatest.flatspec.AnyFlatSpec
import sigma.ast.ErgoTree
import sigma.data.ProveDlog
import sigmastate.crypto.DLogProtocol.DLogProverInput

import scala.concurrent.duration._

/**
  * Where does a mandatory transaction (GenerateCandidate's txsToInclude, as sent by
  * /mining/candidateWithTxs) go, and does the work message prove it?
  *
  * The two tests differ only in the network type, which sets the genesis block version:
  * "tests" launches with the mainnet parameters (version 1), "devnet60" with version 4.
  * The transaction is the same shape in both: it spends the newest reward box to a plain key.
  *
  * Claim: at version 1 the transaction is in the candidate's block transactions and the work
  * message carries a membership proof for it; at version 4 it is in the input-block transactions
  * only, not in the block transactions, and the work message carries no proof.
  * Counts against the claim: the version-4 test failing on any of its three assertions, or the
  * version-1 control failing (then the fixture, not the version, explains the result).
  */
class MandatoryTxPlacementSpec extends AnyFlatSpec with ErgoTestHelpers {
  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  implicit private val timeout: Timeout = defaultTimeout

  private def settingsFor(networkType: NetworkType): ErgoSettings = {
    val empty = ErgoSettingsReader.read()
    val nodeSettings = empty.nodeSettings.copy(mining = true,
      stateType = StateType.Utxo,
      internalMinerPollingInterval = 2.second,
      offlineGeneration = true,
      verifyTransactions = true)
    val chainSettings = empty.chainSettings.copy(blockInterval = 2.seconds)
    empty.copy(nodeSettings = nodeSettings, chainSettings = chainSettings, networkType = networkType,
      directory = createTempDir.getAbsolutePath)
  }

  /** Mines one block, then asks for a candidate with one mandatory transaction. */
  private def candidateWithMandatoryTx(networkType: NetworkType)
                                      (implicit system: ActorSystem): (Candidate, ErgoTransaction) = {
    val settings = settingsFor(networkType)
    val probe = new TestProbe(system)
    system.eventStream.subscribe(probe.ref, classOf[FullBlockApplied])

    val nodeViewHolderRef: ActorRef = ErgoNodeViewRef(settings)
    val readersHolderRef: ActorRef = ErgoReadersHolderRef(nodeViewHolderRef)
    val minerRef: ActorRef = ErgoMiner(settings, nodeViewHolderRef, readersHolderRef, Some(defaultMinerSecret))
    probe.expectNoMessage(1.second)
    minerRef ! StartMining
    probe.expectMsgClass(30.seconds, classOf[FullBlockApplied])

    val r: Readers = await((readersHolderRef ? GetReaders).mapTo[Readers])
    val box: ErgoBox = r.h.bestFullBlockOpt.get.transactions.last.outputs.last
    val payee: ProveDlog = DLogProverInput(BigIntegers.fromUnsignedByteArray("payee".getBytes())).publicImage
    val out = new ErgoBoxCandidate(box.value, ErgoTree.fromSigmaBoolean(payee), r.s.stateContext.currentHeight)
    val unsigned = new UnsignedErgoTransaction(IndexedSeq(Input(box.id, emptyProverResult)), IndexedSeq(), IndexedSeq(out))
    val tx = ErgoTransaction(defaultProver.sign(unsigned, IndexedSeq(box), IndexedSeq(), r.s.stateContext).get)

    val candidate = await(minerRef.askWithStatus(
      GenerateCandidate(Seq(tx), reply = true, forced = false, optPk = None)).mapTo[Candidate])
    candidate -> tx
  }

  private def report(c: Candidate, tx: ErgoTransaction): Unit = {
    val proofs = c.externalVersion.proofsForMandatoryTransactions.map(_.txProofs.map(_.txId))
    info(s"version=${c.candidateBlock.version} " +
      s"inBlockTransactions=${c.candidateBlock.transactions.exists(_.id == tx.id)} " +
      s"inInputBlockTransactions=${c.candidateBlock.inputBlockTransactions.exists(_.id == tx.id)} " +
      s"provedIds=${proofs.getOrElse(Seq.empty).mkString(",")} mandatory=${tx.id}")
  }

  it should "prove a mandatory transaction at block version 1 (control)" in new TestKit(ActorSystem()) {
    try {
      val (c, tx) = candidateWithMandatoryTx(NetworkType.Tests)
      report(c, tx)
      c.candidateBlock.version should be < Header.Interpreter60Version
      c.candidateBlock.transactions.map(_.id) should contain(tx.id)
      c.externalVersion.proofsForMandatoryTransactions.get.txProofs.map(_.txId) shouldBe Seq(tx.id)
    } finally TestKit.shutdownActorSystem(system)
  }

  it should "at block version 4, keep a mandatory transaction out of the block and its proof" in new TestKit(ActorSystem()) {
    try {
      val (c, tx) = candidateWithMandatoryTx(NetworkType.DevNet60)
      report(c, tx)
      c.candidateBlock.version shouldBe Header.Interpreter60Version
      c.candidateBlock.inputBlockTransactions.map(_.id) should contain(tx.id)
      c.candidateBlock.transactions.map(_.id) should not contain tx.id
      c.externalVersion.proofsForMandatoryTransactions.get.txProofs shouldBe empty
    } finally TestKit.shutdownActorSystem(system)
  }
}
