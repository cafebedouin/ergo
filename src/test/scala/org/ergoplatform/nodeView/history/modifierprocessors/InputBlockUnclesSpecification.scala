package org.ergoplatform.nodeView.history.modifierprocessors

import org.ergoplatform.subblocks.InputBlockUncles
import org.ergoplatform.utils.{ErgoCorePropertyTest, InputBlockUnclesTestHelpers}
import scorex.util.{bytesToId, idToBytes}

/**
  * Header-level input-block uncles (node setting `inputBlockUncles`): a later input block may reference up to two
  * PoW-valid siblings for credit. Their transactions are not executed or collected. The rule is non-strict: a
  * missing, stripped or failing reference never makes a block invalid, it only goes uncredited.
  */
class InputBlockUnclesSpecification extends ErgoCorePropertyTest with InputBlockUnclesTestHelpers {

  property("a referenced sibling is credited, the block is valid and L does not include the sibling's transactions") {
    val (h, us) = setup()
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val cTx = spend(boxes(3))
    val c = announce(h, us, Some(b.id), Seq(cTx), uncles = Seq(s.id))
    process(h, us, c, Seq(cTx)) shouldBe (Seq(c.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(c.id, b.id, a.id)
    h.getCreditedUncles(c.id) shouldBe Seq(s.id)
    collectedIds(h) shouldBe Seq(boxes(0), boxes(1), boxes(3)).map(bx => spend(bx).id)
  }

  property("L of a block with uncles equals L of the same block without them") {
    val withUncles = {
      val (h, us) = setup()
      val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
      val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
      process(h, us, c, Seq(spend(boxes(3))))._1 shouldBe Seq(c.id)
      h.getCreditedUncles(c.id) shouldBe Seq(s.id)
      collectedIds(h)
    }
    val withoutUncles = {
      val (h, us) = setup()
      val (_, b, _) = chainWithSibling(h, us, Seq(spend(boxes(2))))
      val c = announceV1(h, us, Some(b.id), Seq(spend(boxes(3))))
      process(h, us, c, Seq(spend(boxes(3))))._1 shouldBe Seq(c.id)
      h.getCreditedUncles(c.id) shouldBe Seq.empty
      collectedIds(h)
    }
    withUncles shouldBe withoutUncles
  }

  property("two siblings referenced are both credited") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val s2 = announce(h, us, None, Seq.empty)
    h.applyInputBlock(s2) shouldBe None
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id, s2.id))
    process(h, us, c, Seq(spend(boxes(3))))._1 shouldBe Seq(c.id)
    h.getCreditedUncles(c.id) shouldBe Seq(s.id, s2.id)
  }

  property("a sibling's transactions are not needed: an announced sibling without transactions is credited") {
    val (h, us) = setup()
    val (a, b, _) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    // a child of A: a child of B would extend the best fork's unprocessed tail, so that C would not be processed
    val bare = announce(h, us, Some(a.id), Seq(spend(boxes(4))))
    h.applyInputBlock(bare) shouldBe None
    h.getInputBlockTransactions(bare.id) shouldBe None
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(bare.id))
    process(h, us, c, Seq(spend(boxes(3))))._1 shouldBe Seq(c.id)
    h.getCreditedUncles(c.id) shouldBe Seq(bare.id)
  }

  property("three ids: block valid, nothing credited") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val s2 = announce(h, us, None, Seq.empty)
    h.applyInputBlock(s2) shouldBe None
    val ids = Seq(s.id, s2.id, b.id)
    val c = announceRaw(freshInputBlockHeader(h, us), Some(b.id), Seq(spend(boxes(3))),
      InputBlockUncles.fieldValue(ids.map(idToBytes)), Array(3.toByte) ++ InputBlockUncles.fieldValue(ids.map(idToBytes)))
    c.uncleIdsOpt shouldBe None
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    h.getCreditedUncles(c.id) shouldBe Seq.empty
  }

  property("malformed length: block valid, nothing credited") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val txs = Seq(spend(boxes(3)))
    // extension field of 33 bytes, announcement repeating the first 32 of them: not the committed leaf
    val c = announceRaw(freshInputBlockHeader(h, us), Some(b.id), txs, idToBytes(s.id) :+ 0.toByte,
      InputBlockUncles.announcementBytes(Seq(s.id)))
    c.unclesCommitted shouldBe false
    process(h, us, c, txs) shouldBe (Seq(c.id) -> Seq.empty)
    h.getCreditedUncles(c.id) shouldBe Seq.empty

    // well-formed field, announcement bytes cut short
    val d = announceRaw(freshInputBlockHeader(h, us), Some(c.id), Seq(spend(boxes(4))), idToBytes(s.id),
      Array(1.toByte) ++ idToBytes(s.id).take(20))
    d.uncleIdsOpt shouldBe None
    process(h, us, d, Seq(spend(boxes(4)))) shouldBe (Seq(d.id) -> Seq.empty)
    h.getCreditedUncles(d.id) shouldBe Seq.empty
  }

  property("duplicate id: block valid, nothing credited") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id, s.id))
    c.unclesCommitted shouldBe true
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    h.getCreditedUncles(c.id) shouldBe Seq.empty
  }

  // A real block can not reference itself (its id commits to the field), so the self-reference rule is checked on
  // the field rule shared by the generator and the validator.
  property("self-reference: the field rule rejects it") {
    val self = bytesToId(Array.fill(32)(7.toByte))
    val other = bytesToId(Array.fill(32)(1.toByte))
    InputBlockUncles.fieldViolation(self, Seq(other)) shouldBe None
    InputBlockUncles.fieldViolation(self, Seq(self)).isDefined shouldBe true
    InputBlockUncles.fieldViolation(self, Seq(other, self)).isDefined shouldBe true
  }

  property("uncle from another ordering block: block valid, that reference uncredited") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val otherOrdering = bytesToId(Array.fill(32)(3.toByte))
    val foreign = announceOn(freshInputBlockHeader(h, us).copy(parentId = otherOrdering), None, Seq.empty)
    h.applyInputBlock(foreign) shouldBe None
    h.getInputBlock(foreign.id).isDefined shouldBe true
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(foreign.id, s.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    h.getCreditedUncles(c.id) shouldBe Seq(s.id)
  }

  property("uncle whose parent is not on the chain, or which is on the chain: block valid, uncredited") {
    val (h, us) = setup()
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    // T is a child of the sibling S: its parent is not on C's chain A, B
    val t = announce(h, us, Some(s.id), Seq.empty)
    h.applyInputBlock(t) shouldBe None
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(t.id, s.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    h.getCreditedUncles(c.id) shouldBe Seq(s.id)

    // an element of the chain is not an uncle
    val d = announce(h, us, Some(c.id), Seq(spend(boxes(4))), uncles = Seq(a.id))
    process(h, us, d, Seq(spend(boxes(4)))) shouldBe (Seq(d.id) -> Seq.empty)
    h.getCreditedUncles(d.id) shouldBe Seq.empty
  }

  property("uncle already credited to an ancestor: block valid, that reference uncredited") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    process(h, us, c, Seq(spend(boxes(3))))._1 shouldBe Seq(c.id)
    h.getCreditedUncles(c.id) shouldBe Seq(s.id)

    val s2 = announce(h, us, Some(b.id), Seq.empty)
    h.applyInputBlock(s2) shouldBe None
    val d = announce(h, us, Some(c.id), Seq(spend(boxes(4))), uncles = Seq(s.id, s2.id))
    process(h, us, d, Seq(spend(boxes(4)))) shouldBe (Seq(d.id) -> Seq.empty)
    h.getCreditedUncles(d.id) shouldBe Seq(s2.id)
  }

  property("unknown id: block valid, uncredited; credited once the sibling's announcement arrives") {
    val (h, us) = setup()
    val (_, b, _) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val late = announce(h, us, Some(b.id), Seq.empty)
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(late.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    h.getCreditedUncles(c.id) shouldBe Seq.empty

    // re-evaluated when the missing announcement arrives
    h.applyInputBlock(late) shouldBe None
    h.getCreditedUncles(c.id) shouldBe Seq(late.id)
    h.bestInputBlock().map(_.id) shouldBe Some(c.id)
  }

  property("stripped field: block valid, no credit") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val txs = Seq(spend(boxes(3)))
    val full = announce(h, us, Some(b.id), txs, uncles = Seq(s.id))
    val c = stripped(full, Some(b.id), txs, Seq(s.id))
    c.id shouldBe full.id
    c.merkleProof.valid(c.header.extensionRoot) shouldBe true
    c.uncleIdsOpt shouldBe None
    process(h, us, c, txs) shouldBe (Seq(c.id) -> Seq.empty)
    h.getCreditedUncles(c.id) shouldBe Seq.empty
    // the full copy arriving later is a known block: nothing changes
    h.applyInputBlock(full) shouldBe None
    h.getCreditedUncles(c.id) shouldBe Seq.empty
  }

  property("version 1 announcements (flag-off and older Matrix nodes) are accepted and processed with uncles enabled") {
    val (h, us) = setup()
    val a = announceV1(h, us, None, Seq(spend(boxes(0))))
    process(h, us, a, Seq(spend(boxes(0)))) shouldBe (Seq(a.id) -> Seq.empty)
    val b = announceV1(h, us, Some(a.id), Seq(spend(boxes(1))))
    process(h, us, b, Seq(spend(boxes(1)))) shouldBe (Seq(b.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(b.id, a.id)
    h.getCreditedUncles(b.id) shouldBe Seq.empty
  }

  property("uncle candidates: siblings whose parent is on the tip's chain, by arrival, not credited along it") {
    val (h, us) = setup()
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    // arrives after S but carries an earlier timestamp: arrival order wins
    val early = announceOn(freshInputBlockHeader(h, us).copy(timestamp = s.header.timestamp - 100000), Some(a.id),
      Seq.empty)
    h.applyInputBlock(early) shouldBe None
    val third = announce(h, us, Some(a.id), Seq.empty)
    h.applyInputBlock(third) shouldBe None
    val offChain = announce(h, us, Some(s.id), Seq.empty)
    h.applyInputBlock(offChain) shouldBe None
    h.bestInputBlock().map(_.id) shouldBe Some(b.id)

    h.uncleCandidates() shouldBe Seq(s.id, early.id)

    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    process(h, us, c, Seq(spend(boxes(3))))._1 shouldBe Seq(c.id)
    h.uncleCandidates() shouldBe Seq(early.id, third.id)
  }

  property("sibling announcements are told apart from announcements extending the chain") {
    val (h, us) = setup()
    val (a, b, _) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    h.isSiblingAnnouncement(announce(h, us, Some(a.id), Seq.empty)) shouldBe true
    h.isSiblingAnnouncement(announce(h, us, None, Seq.empty)) shouldBe true
    h.isSiblingAnnouncement(announce(h, us, Some(b.id), Seq.empty)) shouldBe false
    // a known block is not announced again
    h.isSiblingAnnouncement(b) shouldBe false
  }

  property("uncles disabled: references ignored, nothing credited, no candidates, no sibling relay") {
    val (h, us) = setup(uncles = false)
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(c.id, b.id, a.id)
    h.getCreditedUncles(c.id) shouldBe Seq.empty
    h.uncleCandidates() shouldBe Seq.empty
    h.isSiblingAnnouncement(announce(h, us, Some(a.id), Seq.empty)) shouldBe false
  }

}
