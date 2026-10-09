package org.ergoplatform.mining

import org.ergoplatform.AutolykosSolution
import org.ergoplatform.mining.CandidateGenerator.Candidate
import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.state.UtxoState
import org.ergoplatform.settings.{Algos, ErgoSettings, ErgoValidationSettingsUpdate, Parameters}
import org.ergoplatform.subblocks.{InputBlockAnnouncement, InputBlockUncles}
import org.ergoplatform.utils.{ErgoCorePropertyTest, InputBlockUnclesTestHelpers}
import scorex.crypto.authds.LeafData
import scorex.util.bytesToId
import sigma.crypto.CryptoConstants

/**
  * Candidate generation with header-level input-block uncles (node setting `inputBlockUncles`): a candidate
  * references up to two PoW-valid siblings for credit, by local arrival order; their transactions are not
  * collected. An input-block solution is judged against the candidate it was mined on.
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

  private def candidate(h: ErgoHistory, us: UtxoState, s: ErgoSettings): Candidate = {
    CandidateGenerator.createCandidate(defaultMinerPk, h, ErgoValidationSettingsUpdate.empty, us,
      Seq.empty, None, Seq.empty, s).get._1
  }

  private def uncleIdsOf(c: Candidate) = c.candidateBlock.inputBlockFields.uncleIds.map(_.map(bytesToId))

  private def hasUnclesKey(c: Candidate): Boolean =
    c.candidateBlock.extension.fields.exists(_._1.sameElements(Extension.InputBlockUnclesKey))

  property("generator references a seen PoW-valid sibling and the block validates, with the sibling credited") {
    val (h, us) = setup()
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    h.uncleCandidates() shouldBe Seq(s.id)

    val c = candidate(h, us, unclesSettings)
    val block = c.candidateBlock
    uncleIdsOf(c) shouldBe Some(Seq(s.id))
    block.inputBlockFields.prevInputBlockId.map(bytesToId) shouldBe Some(b.id)
    // L is the base's: the collected transactions of A and B, the sibling's transaction is not collected
    block.transactions.take(2).map(_.id) shouldBe Seq(boxes(0), boxes(1)).map(bx => spend(bx).id)
    block.transactions.map(_.id) should not contain spend(boxes(2)).id

    val (sbi, sbt) = CandidateGenerator.completeInputBlock(block, solution)
    sbi.version shouldBe InputBlockUncles.UnclesMessageVersion
    sbi.uncleIds shouldBe Seq(s.id)
    sbi.unclesCommitted shouldBe true
    sbi.merkleProof.valid(sbi.header.extensionRoot) shouldBe true

    process(h, us, sbi, sbt.transactions) shouldBe (Seq(sbi.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(sbi.id, b.id, a.id)
    h.getCreditedUncles(sbi.id) shouldBe Seq(s.id)
    collectedIds(h).take(2) shouldBe Seq(boxes(0), boxes(1)).map(bx => spend(bx).id)
    collectedIds(h) should not contain spend(boxes(2)).id
    h.uncleCandidates() shouldBe Seq.empty
  }

  property("generator takes at most two siblings, by arrival, skipping the ones credited along the chain") {
    val (h, us) = setup()
    val (a, b, s1) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s1.id))
    process(h, us, c, Seq(spend(boxes(3))))._1 shouldBe Seq(c.id)
    // S3 arrives after S2 with an earlier timestamp, S4 last
    val s2 = announce(h, us, Some(a.id), Seq.empty)
    val s3 = announceOn(freshInputBlockHeader(h, us).copy(timestamp = s2.header.timestamp - 100000), Some(b.id), Seq.empty)
    val s4 = announce(h, us, Some(a.id), Seq.empty)
    Seq(s2, s3, s4).foreach(ib => h.applyInputBlock(ib) shouldBe None)

    val cand = candidate(h, us, unclesSettings)
    cand.candidateBlock.inputBlockFields.prevInputBlockId.map(bytesToId) shouldBe Some(c.id)
    uncleIdsOf(cand) shouldBe Some(Seq(s2.id, s3.id))

    val (sbi, sbt) = CandidateGenerator.completeInputBlock(cand.candidateBlock, solution)
    process(h, us, sbi, sbt.transactions) shouldBe (Seq(sbi.id) -> Seq.empty)
    h.getCreditedUncles(sbi.id) shouldBe Seq(s2.id, s3.id)
    h.uncleCandidates() shouldBe Seq(s4.id)
  }

  property("generator does not reference siblings of another ordering block or off the chain") {
    val (h, us) = setup()
    val (_, _, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val foreign = announceOn(freshInputBlockHeader(h, us).copy(parentId = bytesToId(Array.fill(32)(3.toByte))), None,
      Seq.empty)
    val offChain = announce(h, us, Some(s.id), Seq.empty)
    Seq(foreign, offChain).foreach(ib => h.applyInputBlock(ib) shouldBe None)
    uncleIdsOf(candidate(h, us, unclesSettings)) shouldBe Some(Seq(s.id))
  }

  property("no sibling: no uncles field and a version 1 announcement") {
    val (h, us) = setup()
    val a = announce(h, us, None, Seq(spend(boxes(0))))
    process(h, us, a, Seq(spend(boxes(0))))._1 shouldBe Seq(a.id)
    val c = candidate(h, us, unclesSettings)
    c.candidateBlock.inputBlockFields.uncleIds shouldBe None
    hasUnclesKey(c) shouldBe false
    CandidateGenerator.completeInputBlock(c.candidateBlock, solution)._1.version shouldBe
      InputBlockAnnouncement.initialMessageVersion
  }

  property("cached candidate is stale when the uncle selection changes") {
    val (h, us) = setup()
    val (_, _, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = candidate(h, us, unclesSettings)
    val tip = h.bestInputBlock().map(_.id)
    val digest = Algos.merkleTreeRoot(
      h.getBestOrderingCollectedInputBlocksTransactions().map(tx => LeafData @@ tx.serializedId))
    CandidateGenerator.cachedFor(Some(c), Seq.empty, defaultMinerPk, tip, digest, Seq(s.id)) shouldBe true
    CandidateGenerator.cachedFor(Some(c), Seq.empty, defaultMinerPk, tip, digest, Seq.empty) shouldBe false
    CandidateGenerator.cachedFor(Some(c), Seq.empty, defaultMinerPk, tip, digest,
      Seq(s.id, bytesToId(Array.fill(32)(9.toByte)))) shouldBe false
  }

  property("input-block solution is judged against the candidate it was mined on") {
    val (h, us) = setup()
    chainWithSibling(h, us, Seq(spend(boxes(2))))
    val older = candidate(h, us, unclesSettings)
    val newer = older.copy(candidateBlock = older.candidateBlock.copy(timestamp = older.candidateBlock.timestamp + 1))
    val (olderBlock, _) = CandidateGenerator.completeInputBlock(older.candidateBlock, solution)
    val (newerBlock, _) = CandidateGenerator.completeInputBlock(newer.candidateBlock, solution)
    olderBlock.id should not be newerBlock.id

    val minedOnOlder = (header: Header, _: Parameters) => header.id == olderBlock.id
    CandidateGenerator.inputSolutionCandidate(Seq(newer, older), solution, minedOnOlder).map(_._1) shouldBe Some(older)
    CandidateGenerator.inputSolutionCandidate(Seq(newer, older), solution, minedOnOlder).map(_._2.id) shouldBe
      Some(olderBlock.id)
    // only the current candidate considered: not found
    CandidateGenerator.inputSolutionCandidate(Seq(newer), solution, minedOnOlder) shouldBe None
  }

  property("uncles disabled: no uncles field, version 1 announcement, same candidate cache key as the base") {
    val (h, us) = setup(uncles = false)
    chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = candidate(h, us, settings)
    c.candidateBlock.inputBlockFields.uncleIds shouldBe None
    hasUnclesKey(c) shouldBe false
    val (sbi, _) = CandidateGenerator.completeInputBlock(c.candidateBlock, solution)
    sbi.version shouldBe InputBlockAnnouncement.initialMessageVersion
    sbi.uncleIdsOpt shouldBe None
    sbi.unparsedBytes.isEmpty shouldBe true
  }

  property("mixed: a flag-on node accepts a flag-off node's input blocks") {
    val (h, us) = setup()
    val (a, b, _) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    // generated by a node with uncles disabled, over the same chain
    val c = candidate(h, us, settings)
    hasUnclesKey(c) shouldBe false
    val (sbi, sbt) = CandidateGenerator.completeInputBlock(c.candidateBlock, solution)
    sbi.version shouldBe InputBlockAnnouncement.initialMessageVersion
    process(h, us, sbi, sbt.transactions) shouldBe (Seq(sbi.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(sbi.id, b.id, a.id)
    h.getCreditedUncles(sbi.id) shouldBe Seq.empty
  }

  property("mixed: a flag-off node parses, ignores and relays unchanged a version 2 announcement with 0x03 0x03") {
    val (h, us) = setup(uncles = false)
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    // as a flag-on node announces it
    val withUncle = announce(h, us, Some(b.id), Seq.empty, uncles = Seq(s.id))
    val bytes = InputBlockAnnouncement.serializer.toBytes(withUncle)
    val parsed = InputBlockAnnouncement.serializer.parseBytes(bytes)
    parsed.version shouldBe InputBlockUncles.UnclesMessageVersion
    parsed.merkleProof.valid(parsed.header.extensionRoot) shouldBe true
    // relayed byte for byte (the synchronizer serializes the stored announcement)
    InputBlockAnnouncement.serializer.toBytes(parsed).toSeq shouldBe bytes.toSeq
    process(h, us, parsed, Seq.empty) shouldBe (Seq(parsed.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(parsed.id, b.id, a.id)
    h.getInputBlock(parsed.id).map(ib => InputBlockAnnouncement.serializer.toBytes(ib).toSeq) shouldBe Some(bytes.toSeq)
    h.getCreditedUncles(parsed.id) shouldBe Seq.empty
  }

}
