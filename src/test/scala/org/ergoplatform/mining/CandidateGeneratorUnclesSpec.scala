package org.ergoplatform.mining

import org.ergoplatform.AutolykosSolution
import org.ergoplatform.mining.CandidateGenerator.Candidate
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.state.UtxoState
import org.ergoplatform.settings.{ErgoSettings, ErgoValidationSettingsUpdate}
import org.ergoplatform.subblocks.InputBlockUncles
import org.ergoplatform.utils.{ErgoCorePropertyTest, InputBlockUnclesTestHelpers}
import scorex.util.{ModifierId, bytesToId}
import sigma.crypto.CryptoConstants

import scala.util.{Failure, Success, Try}

/**
  * Candidate generation with input-block uncles (node setting `inputBlockUncles`): a candidate references up to
  * two valid, mergeable, non-conflicting siblings, and an input-block solution is judged against the candidate it
  * was mined on.
  */
class CandidateGeneratorUnclesSpec extends ErgoCorePropertyTest with InputBlockUnclesTestHelpers {

  import org.ergoplatform.utils.ErgoCoreTestConstants.defaultMinerPk
  import org.ergoplatform.utils.ErgoNodeTestConstants.settings

  private val unclesSettings: ErgoSettings =
    settings.copy(nodeSettings = settings.nodeSettings.copy(inputBlockUncles = true))

  private val solution = new AutolykosSolution(
    defaultMinerPk.value,
    CryptoConstants.dlogGroup.generator,
    Array.fill(8)(0.toByte),
    BigInt(0)
  )

  private def sibling(seed: Int, txs: Seq[ErgoTransaction]): (ModifierId, Seq[ErgoTransaction]) =
    bytesToId(Array.fill(32)(seed.toByte)) -> txs

  // validation as by the state, with a fixed cost per transaction
  private def validCost(perTx: Long)(txs: Seq[ErgoTransaction], prefix: Seq[ErgoTransaction]): Try[Long] =
    Success(perTx * txs.size)

  private def candidate(h: ErgoHistory, us: UtxoState, s: ErgoSettings): Candidate = {
    CandidateGenerator.createCandidate(defaultMinerPk, h, ErgoValidationSettingsUpdate.empty, us,
      Seq.empty, None, Seq.empty, s).get._1
  }

  property("selectUncles takes valid siblings oldest first, at most two") {
    val prefix = Seq(spend(boxes(0)))
    val candidates = Seq(
      sibling(1, Seq(spend(boxes(1)))),
      sibling(2, Seq(spend(boxes(2)))),
      sibling(3, Seq(spend(boxes(3)))))
    val (ids, txs) = CandidateGenerator.selectUncles(candidates, prefix, 10L, Long.MaxValue, Long.MaxValue, validCost(5))
    ids shouldBe candidates.take(InputBlockUncles.MaxUncles).map(_._1)
    txs.map(_.id) shouldBe Seq(spend(boxes(1)).id, spend(boxes(2)).id)
  }

  property("selectUncles skips a conflicting sibling and deduplicates collected transactions") {
    val prefix = Seq(spend(boxes(0)), spend(boxes(1)))
    val conflicting = sibling(1, Seq(split(boxes(1))))
    // repeats a collected transaction, adds a new one
    val overlapping = sibling(2, Seq(spend(boxes(1)), spend(boxes(2))))
    // conflicts with the selected sibling above, not with the prefix
    val conflictingWithUncle = sibling(3, Seq(split(boxes(2))))
    val (ids, txs) = CandidateGenerator.selectUncles(Seq(conflicting, overlapping, conflictingWithUncle), prefix,
      0L, Long.MaxValue, Long.MaxValue, validCost(1))
    ids shouldBe Seq(overlapping._1)
    txs.map(_.id) shouldBe Seq(spend(boxes(2)).id)
  }

  property("selectUncles skips siblings which do not fit the limits or are not valid after the prefix") {
    val prefix = Seq(spend(boxes(0)))
    val big = sibling(1, Seq(spend(boxes(1)), spend(boxes(2))))
    val invalid = sibling(2, Seq(spend(boxes(3))))
    val small = sibling(3, Seq(spend(boxes(4))))
    val validate: (Seq[ErgoTransaction], Seq[ErgoTransaction]) => Try[Long] = { (txs, prefixTxs) =>
      if (txs.exists(_.id == spend(boxes(3)).id)) Failure(new Exception("invalid")) else validCost(10)(txs, prefixTxs)
    }
    // prefix costs 100, room for 15 more: the sibling with two transactions (20) does not fit, one with one (10) does
    val (ids, _) = CandidateGenerator.selectUncles(Seq(big, invalid, small), prefix, 100L, 115L, Long.MaxValue, validate)
    ids shouldBe Seq(small._1)

    // size limit: nothing fits beyond the prefix
    val prefixSize = prefix.map(_.size.toLong).sum
    CandidateGenerator.selectUncles(Seq(small), prefix, 0L, Long.MaxValue, prefixSize, validCost(1))._1 shouldBe Seq.empty
  }

  property("cached candidate is stale when uncle candidates change") {
    val (h, us) = setup()
    val (_, _, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = candidate(h, us, unclesSettings)
    c.consideredUncles shouldBe Seq(s.id)
    val tip = h.bestInputBlock().map(_.id)
    val digest = org.ergoplatform.settings.Algos.merkleTreeRoot(
      h.getBestOrderingCollectedInputBlocksTransactions().map(tx => scorex.crypto.authds.LeafData @@ tx.serializedId))
    CandidateGenerator.cachedFor(Some(c), Seq.empty, defaultMinerPk, tip, digest, Seq(s.id)) shouldBe true
    CandidateGenerator.cachedFor(Some(c), Seq.empty, defaultMinerPk, tip, digest, Seq.empty) shouldBe false
    CandidateGenerator.cachedFor(Some(c), Seq.empty, defaultMinerPk, tip, digest,
      Seq(s.id, bytesToId(Array.fill(32)(9.toByte)))) shouldBe false
  }

  property("generator references a waiting sibling and the resulting input block validates") {
    val (h, us) = setup()
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    h.mergeableUncleCandidates() shouldBe Seq(s.id)

    val c = candidate(h, us, unclesSettings)
    val block = c.candidateBlock
    block.inputBlockFields.uncleIds.map(_.map(bytesToId)) shouldBe Some(Seq(s.id))
    block.inputBlockFields.prevInputBlockId.map(bytesToId) shouldBe Some(b.id)
    // the ordering-block transactions start with L(B) followed by the uncle's transactions
    block.transactions.take(3).map(_.id) shouldBe Seq(boxes(0), boxes(1), boxes(2)).map(bx => spend(bx).id)

    val (sbi, sbt) = CandidateGenerator.completeInputBlock(block, solution)
    sbi.version shouldBe InputBlockUncles.UnclesMessageVersion
    sbi.uncleIds shouldBe Seq(s.id)
    sbi.unclesCommitted shouldBe true
    sbi.merkleProof.valid(sbi.header.extensionRoot) shouldBe true

    process(h, us, sbi, sbt.transactions) shouldBe (Seq(sbi.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(sbi.id, b.id, a.id)
    collectedIds(h).take(3) shouldBe Seq(boxes(0), boxes(1), boxes(2)).map(bx => spend(bx).id)
    h.mergeableUncleCandidates() shouldBe Seq.empty
  }

  property("generator does not reference a conflicting sibling") {
    val (h, us) = setup()
    // valid against its own prefix, conflicts with B
    val (_, _, s) = chainWithSibling(h, us, Seq(split(boxes(1))))
    h.mergeableUncleCandidates() shouldBe Seq(s.id)
    val c = candidate(h, us, unclesSettings)
    c.consideredUncles shouldBe Seq(s.id)
    c.candidateBlock.inputBlockFields.uncleIds.map(_.map(bytesToId)) shouldBe Some(Seq.empty)

    val (sbi, sbt) = CandidateGenerator.completeInputBlock(c.candidateBlock, solution)
    sbi.unclesCommitted shouldBe true
    process(h, us, sbi, sbt.transactions)._1 shouldBe Seq(sbi.id)
  }

  property("input-block solution is judged against the candidate it was mined on") {
    val (h, us) = setup()
    chainWithSibling(h, us, Seq(spend(boxes(2))))
    val older = candidate(h, us, unclesSettings)
    val newer = older.copy(candidateBlock = older.candidateBlock.copy(timestamp = older.candidateBlock.timestamp + 1))
    val (olderBlock, _) = CandidateGenerator.completeInputBlock(older.candidateBlock, solution)
    val (newerBlock, _) = CandidateGenerator.completeInputBlock(newer.candidateBlock, solution)
    olderBlock.id should not be newerBlock.id

    val minedOnOlder = (header: org.ergoplatform.modifiers.history.header.Header, _: org.ergoplatform.settings.Parameters) =>
      header.id == olderBlock.id
    CandidateGenerator.inputSolutionCandidate(Seq(newer, older), solution, minedOnOlder).map(_._1) shouldBe Some(older)
    CandidateGenerator.inputSolutionCandidate(Seq(newer, older), solution, minedOnOlder).map(_._2.id) shouldBe Some(olderBlock.id)
    // only the current candidate considered: not found
    CandidateGenerator.inputSolutionCandidate(Seq(newer), solution, minedOnOlder) shouldBe None
  }

  property("uncles disabled: no uncles field, version 1 announcement, no uncle candidates") {
    val (h, us) = setup(uncles = false)
    chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = candidate(h, us, settings)
    c.consideredUncles shouldBe Seq.empty
    c.candidateBlock.inputBlockFields.uncleIds shouldBe None
    c.candidateBlock.extension.fields.exists(_._1.sameElements(
      org.ergoplatform.modifiers.history.extension.Extension.InputBlockUnclesKey)) shouldBe false
    val (sbi, _) = CandidateGenerator.completeInputBlock(c.candidateBlock, solution)
    sbi.version shouldBe org.ergoplatform.subblocks.InputBlockAnnouncement.initialMessageVersion
    sbi.uncleIdsOpt shouldBe None
  }

}
