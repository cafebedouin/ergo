package org.ergoplatform.nodeView.history.modifierprocessors

import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.subblocks.InputBlockUncles
import org.ergoplatform.utils.{ErgoCorePropertyTest, InputBlockUnclesTestHelpers}
import org.ergoplatform.utils.ErgoCoreTestConstants.parameters
import scorex.util.{bytesToId, idToBytes}

/**
  * Input-block uncles (node setting `inputBlockUncles`): an input block may merge up to two siblings, i.e. input
  * blocks whose parent is an earlier element of its chain. Collected transactions:
  * L(block) = L(parent) ++ deduplicated transactions of each uncle ++ own transactions.
  *
  * In most cases below the chain is A <- B, and S is a sibling of B (child of A); C is a child of B.
  */
class InputBlockUnclesSpecification extends ErgoCorePropertyTest with InputBlockUnclesTestHelpers {

  property("sibling is validated against its own prefix on arrival and becomes an uncle candidate") {
    val (h, us) = setup()
    val (_, _, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    h.getInputBlockValidity(s.id) shouldBe Some(true)
    h.getInputBlockCost(s.id).exists(_ > 0) shouldBe true
    h.mergeableUncleCandidates() shouldBe Seq(s.id)
  }

  property("sibling invalid against its own prefix is recorded invalid and is not an uncle candidate") {
    val (h, us) = setup()
    // spends box 0, already spent by A
    val (_, _, s) = chainWithSibling(h, us, Seq(split(boxes(0))))
    h.getInputBlockValidity(s.id) shouldBe Some(false)
    h.mergeableUncleCandidates() shouldBe Seq.empty
  }

  property("uncle accepted and its transactions collected in order: L(parent), uncle, own") {
    val (h, us) = setup()
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)

    h.bestInputBlocksChain() shouldBe Seq(c.id, b.id, a.id)
    h.getInputBlockUncles(c.id) shouldBe Seq(s.id)
    collectedIds(h) shouldBe Seq(boxes(0), boxes(1), boxes(2), boxes(3)).map(bx => spend(bx).id)
    // merged once only
    h.mergeableUncleCandidates() shouldBe Seq.empty
    // the cost of C covers the uncle's transactions and its own
    h.getInputBlockCost(c.id).get should be > h.getInputBlockCost(s.id).get
  }

  property("uncle transactions already collected are deduplicated (first appearance kept)") {
    val (h, us) = setup()
    // S repeats B's transaction (spend of box 1) and adds a new one
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(1)), spend(boxes(2))))
    h.getInputBlockValidity(s.id) shouldBe Some(true)
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    collectedIds(h) shouldBe Seq(boxes(0), boxes(1), boxes(2), boxes(3)).map(bx => spend(bx).id)
  }

  property("sibling conflicting with the collected transactions is not mergeable") {
    val (h, us) = setup()
    // S spends box 1 differently from B: valid against its own prefix (A), conflicting with L(B)
    val (a, b, s) = chainWithSibling(h, us, Seq(split(boxes(1))))
    h.getInputBlockValidity(s.id) shouldBe Some(true)
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq.empty -> Seq.empty)
    h.getInputBlockValidity(c.id) shouldBe Some(false)
    h.bestInputBlocksChain() shouldBe Seq(b.id, a.id)
  }

  property("spending an output of a sibling's branch is invalid without referencing it") {
    val (h, us) = setup()
    val sTx = spend(boxes(2))
    val (_, b, _) = chainWithSibling(h, us, Seq(sTx))
    val spendingTx = spend(sTx.outputs.head)
    val c = announce(h, us, Some(b.id), Seq(spendingTx))
    process(h, us, c, Seq(spendingTx)) shouldBe (Seq.empty -> Seq.empty)
    h.getInputBlockValidity(c.id) shouldBe Some(false)
  }

  property("spending an output of an uncle is valid when the uncle is referenced") {
    val (h, us) = setup()
    val sTx = spend(boxes(2))
    val (a, b, s) = chainWithSibling(h, us, Seq(sTx))
    val spendingTx = spend(sTx.outputs.head)
    val c = announce(h, us, Some(b.id), Seq(spendingTx), uncles = Seq(s.id))
    process(h, us, c, Seq(spendingTx)) shouldBe (Seq(c.id) -> Seq.empty)
    h.bestInputBlocksChain() shouldBe Seq(c.id, b.id, a.id)
    collectedIds(h) shouldBe Seq(spend(boxes(0)).id, spend(boxes(1)).id, sTx.id, spendingTx.id)
  }

  property("at most two uncles, each merged once") {
    val (h, us) = setup()
    val (_, b, s1) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val a = h.getInputBlock(b.prevInputBlockId.get).get
    val s2 = announce(h, us, Some(a.id), Seq(spend(boxes(3))))
    process(h, us, s2, Seq(spend(boxes(3)))) shouldBe (Seq.empty -> Seq.empty)
    val s3 = announce(h, us, Some(a.id), Seq(spend(boxes(4))))
    process(h, us, s3, Seq(spend(boxes(4)))) shouldBe (Seq.empty -> Seq.empty)
    h.mergeableUncleCandidates().toSet shouldBe Set(s1.id, s2.id, s3.id)

    // three uncles: announcement and extension can not carry them (the field holds at most two ids)
    InputBlockUncles.parseFieldValue(InputBlockUncles.fieldValue(Seq(s1.id, s2.id, s3.id).map(idToBytes))) shouldBe None

    val c = announce(h, us, Some(b.id), Seq.empty, uncles = Seq(s1.id, s2.id))
    process(h, us, c, Seq.empty) shouldBe (Seq(c.id) -> Seq.empty)
    h.mergeableUncleCandidates() shouldBe Seq(s3.id)

    // an uncle merged by an earlier element can not be merged again
    val d = announce(h, us, Some(c.id), Seq.empty, uncles = Seq(s1.id))
    process(h, us, d, Seq.empty) shouldBe (Seq.empty -> Seq.empty)
    h.getInputBlockValidity(d.id) shouldBe Some(false)
  }

  property("block waiting for its uncle's transactions is processed when they arrive") {
    val (h, us) = setup()
    val a = announce(h, us, None, Seq(spend(boxes(0))))
    process(h, us, a, Seq(spend(boxes(0)))) shouldBe (Seq(a.id) -> Seq.empty)
    val b = announce(h, us, Some(a.id), Seq(spend(boxes(1))))
    process(h, us, b, Seq(spend(boxes(1)))) shouldBe (Seq(b.id) -> Seq.empty)
    val s = announce(h, us, Some(a.id), Seq(spend(boxes(2))))
    h.applyInputBlock(s) shouldBe None

    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    h.missingUncles(c) shouldBe Seq.empty
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq.empty -> Seq.empty)
    // not ready is not a verdict
    h.getInputBlockValidity(c.id) shouldBe None

    h.applyInputBlockTransactions(s.id, Seq(spend(boxes(2))), us)._1 shouldBe Seq(c.id)
    h.bestInputBlocksChain().head shouldBe c.id
  }

  property("unknown uncles are reported as missing") {
    val (h, us) = setup()
    val unknown = bytesToId(Array.fill(32)(7.toByte))
    val c = announce(h, us, None, Seq.empty, uncles = Seq(unknown))
    h.applyInputBlock(c) shouldBe None
    h.missingUncles(c) shouldBe Seq(unknown)
  }

  property("collected transactions over the cost limit make the input block invalid") {
    // measure costs with the default parameters
    val (h0, us0) = setup()
    val (a0, b0, s0) = chainWithSibling(h0, us0, Seq(spend(boxes(2))))
    val costs = Seq(a0, b0, s0).map(ib => h0.getInputBlockCost(ib.id).get)
    val collectedCost = costs.sum // C below has no transactions of its own

    // L(C) = A, B, S exceeds the limit by one; every single input block is far below maxBlockCost
    val maxBlockCost = (InputBlockUncles.RewardCostReserve + collectedCost - 1).toInt
    val (h, us) = setup(params = parameters.withBlockCost(maxBlockCost))
    val (a, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    Seq(a, b, s).map(ib => h.getInputBlockCost(ib.id).get) shouldBe costs
    h.getInputBlockValidity(s.id) shouldBe Some(true)
    val c = announce(h, us, Some(b.id), Seq.empty, uncles = Seq(s.id))
    process(h, us, c, Seq.empty) shouldBe (Seq.empty -> Seq.empty)
    h.getInputBlockValidity(c.id) shouldBe Some(false)

    // exactly at the limit is valid
    val (h2, us2) = setup(params = parameters.withBlockCost(maxBlockCost + 1))
    val (_, b2, s2) = chainWithSibling(h2, us2, Seq(spend(boxes(2))))
    val c2 = announce(h2, us2, Some(b2.id), Seq.empty, uncles = Seq(s2.id))
    process(h2, us2, c2, Seq.empty) shouldBe (Seq(c2.id) -> Seq.empty)
  }

  property("announcement without a committed uncles field is ignored when uncles are enabled") {
    val (h, us) = setup()
    val v1 = announceV1(h, us, None, Seq(spend(boxes(0))))
    h.applyInputBlock(v1) shouldBe None
    h.getInputBlock(v1.id) shouldBe None

    // version 2 announcement repeating uncle ids other than the committed ones
    val forged = announce(h, us, None, Seq(spend(boxes(0))), uncles = Seq.empty,
      announcedUncles = Some(Seq(bytesToId(Array.fill(32)(1.toByte)))))
    forged.unclesCommitted shouldBe false
    h.applyInputBlock(forged) shouldBe None
    h.getInputBlock(forged.id) shouldBe None

    val good = announce(h, us, None, Seq(spend(boxes(0))))
    good.unclesCommitted shouldBe true
    process(h, us, good, Seq(spend(boxes(0)))) shouldBe (Seq(good.id) -> Seq.empty)
  }

  property("uncles disabled: uncle references are ignored, behaviour as without uncles support") {
    val (h, us) = setup(uncles = false)
    val sTx = spend(boxes(2))
    val (_, b, s) = chainWithSibling(h, us, Seq(sTx))
    // siblings are not validated, nothing is recorded
    h.getInputBlockValidity(s.id) shouldBe None
    h.getInputBlockCost(b.id) shouldBe None
    h.mergeableUncleCandidates() shouldBe Seq.empty

    // a block referencing S spending its output is invalid: the reference is ignored
    val c1 = announce(h, us, Some(b.id), Seq(spend(sTx.outputs.head)), uncles = Seq(s.id))
    h.getInputBlockUncles(c1.id) shouldBe Seq.empty
    h.missingUncles(c1) shouldBe Seq.empty
    process(h, us, c1, Seq(spend(sTx.outputs.head))) shouldBe (Seq.empty -> Seq.empty)

    // version 1 announcements are accepted
    val (h2, us2) = setup(uncles = false)
    val (a2, b2, s2) = chainWithSibling(h2, us2, Seq(sTx))
    val c2 = announceV1(h2, us2, Some(b2.id), Seq(spend(boxes(3))))
    process(h2, us2, c2, Seq(spend(boxes(3)))) shouldBe (Seq(c2.id) -> Seq.empty)
    // a referenced uncle's transactions are not collected
    val d2 = announce(h2, us2, Some(c2.id), Seq(spend(boxes(4))), uncles = Seq(s2.id))
    process(h2, us2, d2, Seq(spend(boxes(4)))) shouldBe (Seq(d2.id) -> Seq.empty)
    h2.bestInputBlocksChain() shouldBe Seq(d2.id, c2.id, b2.id, a2.id)
    collectedIds(h2) shouldBe Seq(boxes(0), boxes(1), boxes(3), boxes(4)).map(bx => spend(bx).id)
  }

  property("ordering block transactions rebuilt from L match the transactions root") {
    val (h, us) = setup()
    val (_, b, s) = chainWithSibling(h, us, Seq(spend(boxes(2))))
    val c = announce(h, us, Some(b.id), Seq(spend(boxes(3))), uncles = Seq(s.id))
    process(h, us, c, Seq(spend(boxes(3)))) shouldBe (Seq(c.id) -> Seq.empty)
    val orderingParentId = h.bestFullBlockOpt.get.id
    val own = Seq(spend(boxes(5)))
    val expected = Seq(boxes(0), boxes(1), boxes(2), boxes(3)).map(spend) ++ own

    // an ordering block linking C: its block transactions are L(C) followed by its own ones
    val extFields = Seq(Extension.PrevInputBlockIdKey -> idToBytes(c.id), Extension.InputBlockUnclesKey -> Array.emptyByteArray)
    val rebuilt = h.orderingBlockCollectedTransactions(orderingParentId, extFields).get ++ own
    rebuilt.map(_.id) shouldBe expected.map(_.id)
    BlockTransactions.transactionsRoot(rebuilt, Header.Interpreter60Version) shouldBe
      BlockTransactions.transactionsRoot(expected, Header.Interpreter60Version)

    // the rebuild checked against an ordering block header committing to the expected transactions
    val orderingHeader = freshInputBlockHeader(h, us).copy(version = Header.InitialVersion,
      transactionsRoot = BlockTransactions.transactionsRoot(expected, Header.InitialVersion))
    orderingHeader.parentId shouldBe orderingParentId
    h.rebuildOrderingBlockTransactions(orderingHeader, extFields, own).map(_.map(_.id)) shouldBe Right(expected.map(_.id))
    // own transactions missing: the root does not match, and the message lists the rebuilt ids
    val mismatch = h.rebuildOrderingBlockTransactions(orderingHeader, extFields, Seq.empty)
    mismatch.isLeft shouldBe true
    mismatch.fold(reason => reason, _ => "") should include(spend(boxes(3)).id)

    // an ordering block linking B and merging S itself
    val extFieldsB = Seq(Extension.PrevInputBlockIdKey -> idToBytes(b.id),
      Extension.InputBlockUnclesKey -> InputBlockUncles.fieldValue(Seq(idToBytes(s.id))))
    h.orderingBlockCollectedTransactions(orderingParentId, extFieldsB).get.map(_.id) shouldBe
      Seq(boxes(0), boxes(1), boxes(2)).map(bx => spend(bx).id)

    // unknown input block or uncle: not rebuilt (the node downloads the block transactions instead)
    val unknown = idToBytes(bytesToId(Array.fill(32)(3.toByte)))
    h.orderingBlockCollectedTransactions(orderingParentId, Seq(Extension.PrevInputBlockIdKey -> unknown)) shouldBe None
    h.orderingBlockCollectedTransactions(orderingParentId,
      Seq(Extension.PrevInputBlockIdKey -> idToBytes(c.id), Extension.InputBlockUnclesKey -> unknown)) shouldBe None
    // another ordering block's tree: not rebuilt
    h.orderingBlockCollectedTransactions(c.id, extFields) shouldBe None
  }

}
