package org.ergoplatform.modifiers.history

import org.ergoplatform.modifiers.history.extension.ExtensionCandidate
import org.ergoplatform.modifiers.history.extension.Extension.InterlinksVectorPrefix
import org.ergoplatform.modifiers.history.popow.NipopowAlgos
import org.ergoplatform.modifiers.history.popow.PoPowHeader.{batchMerkleProofEncoder, checkInterlinksProof}
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.scalacheck.Gen
import scorex.util.{ModifierId, idToBytes}

class PoPowHeaderSpec extends ErgoCorePropertyTest {
  import org.ergoplatform.utils.generators.CoreObjectGenerators._
  import org.ergoplatform.utils.ErgoCoreTestConstants._

  property("Check interlinks proof should be true") {
    forAll(Gen.nonEmptyListOf(modifierIdGen)) { interlinks =>
      val interlinksProof = NipopowAlgos.proofForInterlinkVector(nipopowAlgos.interlinksToExtension(interlinks)).get
      checkInterlinksProof(interlinks, interlinksProof) shouldBe true
    }
  }

  property("Check invalid interlinks proof should be false") {
    forAll(Gen.nonEmptyListOf(modifierIdGen), Gen.nonEmptyListOf(modifierIdGen)) { (interlinks1, interlinks2) =>
        val interlinksProof = NipopowAlgos.proofForInterlinkVector(nipopowAlgos.interlinksToExtension(interlinks2)).get
        checkInterlinksProof(interlinks1, interlinksProof) shouldBe false
    }
  }

  property("Interlinks proof for an extension which packs the same interlinks in another way") {
    def field(index: Int, quantity: Int, id: ModifierId): (Array[Byte], Array[Byte]) =
      Array(InterlinksVectorPrefix, index.toByte) -> (quantity.toByte +: idToBytes(id))

    forAll(modifierIdGen, modifierIdGen) { (first, second) =>
      whenever(first != second) {
        val interlinks = Seq(first, second, second, second)
        // unpackInterlinks reads the fields in order: it does not read the second byte of a key, it does not
        // ask that the copies of one id are in one field, and a field may hold no copy at all
        val layouts = Seq(
          "other key indices" -> Seq(field(7, 1, first), field(9, 3, second)),
          "keys out of order" -> Seq(field(9, 1, first), field(7, 3, second)),
          "a run split over two fields" -> Seq(field(0, 1, first), field(1, 1, second), field(2, 2, second)),
          "a field of quantity zero" -> Seq(field(0, 1, first), field(1, 0, first), field(2, 3, second))
        )
        val refused = layouts.collect { case (layout, fields) if {
          NipopowAlgos.unpackInterlinks(fields).get shouldBe interlinks
          val interlinksProof = NipopowAlgos.proofForInterlinkVector(ExtensionCandidate(fields)).get
          !checkInterlinksProof(interlinks, interlinksProof)
        } => layout }
        refused shouldBe empty
      }
    }
  }

  property("Interlinks proof for an extension packed by packInterlinks is the proof in its own fields") {
    // interlinks as a block holds them: each id once or a few times in a row
    val runsGen = Gen.resize(30, Gen.nonEmptyListOf(Gen.zip(modifierIdGen, Gen.choose(1, 4))))
    forAll(runsGen) { runs =>
      whenever(runs.map(_._1).distinct.size == runs.size) {
        val interlinks = runs.flatMap { case (id, copies) => Seq.fill(copies)(id) }
        val packed = NipopowAlgos.packInterlinks(interlinks)
        val keys = packed.map(_._1)
        val withAnotherField = (Array(0: Byte, 1: Byte) -> Array.fill(8)(1: Byte)) +: packed
        Seq(packed, withAnotherField).map(ExtensionCandidate(_)).foreach { extension =>
          val interlinksProof = NipopowAlgos.proofForInterlinkVector(extension).get
          batchMerkleProofEncoder(interlinksProof) shouldBe batchMerkleProofEncoder(extension.batchProofFor(keys: _*).get)
          checkInterlinksProof(interlinks, interlinksProof) shouldBe true
        }
      }
    }
  }
}
