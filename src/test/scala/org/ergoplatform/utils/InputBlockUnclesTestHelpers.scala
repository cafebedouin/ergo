package org.ergoplatform.utils

import com.google.common.io.Files.createTempDir
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, Input}
import org.ergoplatform.mining.InputBlockFields
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.state.{BoxHolder, StateType, UtxoState}
import org.ergoplatform.settings.{Algos, Parameters}
import org.ergoplatform.subblocks.{InputBlockAnnouncement, InputBlockUncles}
import org.ergoplatform.utils.HistoryTestHelpers.generateHistory
import org.ergoplatform.utils.generators.ChainGenerator.{applyChain, genChain}
import org.scalatest.matchers.should.Matchers
import scorex.crypto.authds.LeafData
import scorex.util.{ModifierId, bytesToId, idToBytes}
import sigma.Colls
import sigma.ast.ErgoTree
import sigma.data.TrivialProp.TrueProp
import sigma.interpreter.ProverResult

/**
  * Input blocks with uncles over a history with two ordering blocks applied and a UTXO state of `boxes`
  * (boxes protected by `true`, spent with empty proofs).
  */
trait InputBlockUnclesTestHelpers { self: Matchers =>

  import org.ergoplatform.utils.ErgoNodeTestConstants.settings

  def trueBox(seed: String): ErgoBox = new ErgoBox(
    value = 1000000000L,
    ergoTree = ErgoTree.fromProposition(TrueProp),
    creationHeight = 0,
    additionalTokens = Colls.emptyColl,
    additionalRegisters = Map.empty,
    transactionId = bytesToId(Algos.hash(seed)),
    index = 0
  )

  /** Spends a box into an output of the same value (the same box gives the same transaction). */
  def spend(box: ErgoBox): ErgoTransaction =
    new ErgoTransaction(IndexedSeq(Input(box.id, ProverResult.empty)), IndexedSeq.empty, IndexedSeq(box.toCandidate))

  /** Spends a box into two outputs (same creation height as the box): conflicts with spend(box). */
  def split(box: ErgoBox): ErgoTransaction = {
    val half = box.value / 2
    val height = box.creationHeight
    new ErgoTransaction(IndexedSeq(Input(box.id, ProverResult.empty)), IndexedSeq.empty, IndexedSeq(
      new ErgoBoxCandidate(half, box.ergoTree, height, box.additionalTokens, box.additionalRegisters),
      new ErgoBoxCandidate(box.value - half, box.ergoTree, height, box.additionalTokens, box.additionalRegisters)))
  }

  val boxes: Seq[ErgoBox] = (1 to 6).map(i => trueBox(s"uncles-box-$i"))

  /** History with two ordering blocks applied, and a UTXO state with `boxes`. */
  def setup(uncles: Boolean = true,
            params: Parameters = ErgoCoreTestConstants.parameters): (ErgoHistory, UtxoState) = {
    val us = UtxoState.fromBoxHolder(BoxHolder(boxes), None, createTempDir, settings, params)
    val h = generateHistory(verifyTransactions = true, StateType.Utxo, PoPoWBootstrap = false, blocksToKeep = -1,
      epochLength = 10000, useLastEpochs = 3, initialDiffOpt = None, genesisIdOpt = None, inputBlockUncles = uncles)
    applyChain(h, genChain(2, h, stateOpt = Some(us)))
    (h, us)
  }

  /** A header for a new input block of the best ordering block. */
  def freshInputBlockHeader(h: ErgoHistory, us: UtxoState): Header =
    genChain(2, h, stateOpt = Some(us)).tail.head.header

  /**
    * Input block announcement as the generator makes it with uncles enabled: extension with the uncles field
    * (possibly empty), proof covering it, version 2 message repeating the uncle ids (or `announcedUncles`).
    * The header is `baseHeader` committing to the extension.
    */
  def announceOn(baseHeader: Header,
                 parent: Option[ModifierId],
                 txs: Seq[ErgoTransaction],
                 uncles: Seq[ModifierId] = Seq.empty,
                 announcedUncles: Option[Seq[ModifierId]] = None): InputBlockAnnouncement = {
    val digest = Algos.merkleTreeRoot(txs.map(tx => LeafData @@ tx.serializedId))
    val uncleField = Some(uncles.map(idToBytes))
    val ext = InputBlockFields.toExtensionFields(parent.map(idToBytes), digest, digest, uncleField)
    val fields = new InputBlockFields(parent.map(idToBytes), digest, digest, ext.proofForInputBlockData.get, uncleField)
    InputBlockAnnouncement(InputBlockUncles.UnclesMessageVersion, baseHeader.copy(extensionRoot = ext.digest), fields,
      None, InputBlockUncles.announcementBytes(announcedUncles.getOrElse(uncles)))
  }

  /** `announceOn` a fresh header of the best ordering block. */
  def announce(h: ErgoHistory,
               us: UtxoState,
               parent: Option[ModifierId],
               txs: Seq[ErgoTransaction],
               uncles: Seq[ModifierId] = Seq.empty,
               announcedUncles: Option[Seq[ModifierId]] = None): InputBlockAnnouncement =
    announceOn(freshInputBlockHeader(h, us), parent, txs, uncles, announcedUncles)

  /** Version 1 announcement (as made before uncles support), no uncles field. */
  def announceV1(h: ErgoHistory,
                 us: UtxoState,
                 parent: Option[ModifierId],
                 txs: Seq[ErgoTransaction]): InputBlockAnnouncement = {
    val digest = Algos.merkleTreeRoot(txs.map(tx => LeafData @@ tx.serializedId))
    val ext = InputBlockFields.toExtensionFields(parent.map(idToBytes), digest, digest)
    val fields = new InputBlockFields(parent.map(idToBytes), digest, digest, ext.proofForInputBlockData.get)
    InputBlockAnnouncement(InputBlockAnnouncement.initialMessageVersion,
      freshInputBlockHeader(h, us).copy(extensionRoot = ext.digest), fields, None)
  }

  /** Applies an input block and then its transactions. */
  def process(h: ErgoHistory,
              us: UtxoState,
              ib: InputBlockAnnouncement,
              txs: Seq[ErgoTransaction]): (Seq[ModifierId], Seq[ModifierId]) = {
    h.applyInputBlock(ib) shouldBe None
    h.applyInputBlockTransactions(ib.id, txs, us)
  }

  def collectedIds(h: ErgoHistory): Seq[ModifierId] =
    h.getBestOrderingCollectedInputBlocksTransactions().map(_.id)

  /**
    * Chain A <- B with sibling S of B (child of A). A spends box 0, B spends box 1, S has the transactions given.
    */
  def chainWithSibling(h: ErgoHistory,
                       us: UtxoState,
                       siblingTxs: Seq[ErgoTransaction]): (InputBlockAnnouncement, InputBlockAnnouncement, InputBlockAnnouncement) = {
    val a = announce(h, us, None, Seq(spend(boxes(0))))
    process(h, us, a, Seq(spend(boxes(0)))) shouldBe (Seq(a.id) -> Seq.empty)
    val b = announce(h, us, Some(a.id), Seq(spend(boxes(1))))
    process(h, us, b, Seq(spend(boxes(1)))) shouldBe (Seq(b.id) -> Seq.empty)
    val s = announce(h, us, Some(a.id), siblingTxs)
    // a sibling is not the best tip, its transactions do not make progress
    process(h, us, s, siblingTxs) shouldBe (Seq.empty -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(b.id, a.id)
    (a, b, s)
  }

}
