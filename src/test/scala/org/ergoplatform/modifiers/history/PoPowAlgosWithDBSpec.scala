package org.ergoplatform.modifiers.history

import org.ergoplatform.modifiers.history.popow.{NipopowProverWithDbAlgs, PoPowHeader, PoPowParams}
import org.ergoplatform.nodeView.state.StateType
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec

class PoPowAlgosWithDBSpec extends AnyPropSpec with Matchers {
  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.HistoryTestHelpers._
  import org.ergoplatform.utils.generators.ChainGenerator._

  property("proof(chain) is equivalent to proof(histReader)") {
    val poPowParams = PoPowParams(m = 5, k = 6, continuous = false).get
    val blocksChain = genChain(3000)
    val pchain = blocksChain.map(b => PoPowHeader.fromBlock(b).get)
    val proof0 = nipopowAlgos.prove(pchain)(poPowParams).get

    val h = generateHistory(true, StateType.Digest, false,
      10000, 10000, 10, None)
    val hr = applyChain(h, blocksChain)
    val proof1 = NipopowProverWithDbAlgs.prove(hr, chainSettings = settings.chainSettings)(poPowParams).get

    proof0.suffixHead.id shouldBe proof1.suffixHead.id
    proof0.suffixTail.map(_.id) shouldBe proof1.suffixTail.map(_.id)

    proof0.prefix.map(_.id).length shouldBe proof1.prefix.map(_.id).length
    proof0.prefix.map(_.id).toList shouldBe proof1.prefix.map(_.id).toList
  }

  property("the interlinks proof of a block which starts a voting epoch is the interlinks-only one") {
    val votingLength = 8
    val blocksChain = genChain(20)
    val h = generateHistory(true, StateType.Digest, false, 10000, 10000, 10, votingLength = Some(votingLength))
    val hr = applyChain(h, blocksChain)

    // blocks of the test chain have one extension field besides the interlinks, so the two roots differ:
    // the root, of the two, to which the proof built for the block at a given height leads
    def provenRoot(height: Int): String = {
      val extension = blocksChain(height - 1).extension
      val interlinksProof = hr.popowHeader(height).get.interlinksProof
      (interlinksProof.valid(extension.digest), interlinksProof.valid(extension.interlinksDigest)) match {
        case (true, false) => "extension"
        case (false, true) => "interlinks"
        case other => other.toString
      }
    }
    Seq(votingLength - 1, votingLength, votingLength + 1, 2 * votingLength).map(provenRoot) shouldBe
      Seq("extension", "interlinks", "extension", "interlinks")

    val votingSettings = settings.chainSettings.voting.copy(votingLength = votingLength)
    val proof = NipopowProverWithDbAlgs
      .prove(hr, chainSettings = settings.chainSettings.copy(voting = votingSettings))(PoPowParams(m = 5, k = 6, continuous = false).get)
      .get
    proof.prefix.map(_.height).filter(_ % votingLength == 0) should not be empty
    proof.isValid shouldBe true
  }

  property("proof(histReader) for a header in the past") {
    val poPowParams = PoPowParams(5, 6, continuous = false).get
    val blocksChain = genChain(300)

    val at = 200

    val h = generateHistory(true, StateType.Digest, false,
      10000, 10000, 10, None)
    val hr = applyChain(h, blocksChain.take(at))
    val proof0 = NipopowProverWithDbAlgs.prove(hr, None, chainSettings = settings.chainSettings)(poPowParams).get

    val id = proof0.suffixHead.header.id

    val hrf = applyChain(hr, blocksChain.drop(at))
    val proof1 = NipopowProverWithDbAlgs.prove(hrf, Some(id), chainSettings = settings.chainSettings)(poPowParams).get

    proof0.suffixHead.id shouldBe proof1.suffixHead.id
    proof0.suffixTail.map(_.id) shouldBe proof1.suffixTail.map(_.id)

    proof0.prefix.map(_.id).length shouldBe proof1.prefix.map(_.id).length
    proof0.prefix.map(_.id).sorted.toList shouldBe proof1.prefix.map(_.id).sorted.toList
  }

}
