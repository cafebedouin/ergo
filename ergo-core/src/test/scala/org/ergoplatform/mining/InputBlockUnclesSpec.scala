package org.ergoplatform.mining

import org.ergoplatform.modifiers.history.extension.{Extension, ExtensionCandidate}
import org.ergoplatform.subblocks.{InputBlockAnnouncement, InputBlockUncles}
import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.utils.generators.CoreObjectGenerators._
import org.ergoplatform.utils.generators.ErgoCoreGenerators._
import org.scalacheck.Gen
import scorex.crypto.hash.Digest32
import scorex.util.{ModifierId, bytesToId, idToBytes}

/**
  * Encodings of input-block uncle references: extension field 0x03 0x03 and the version 2 announcement.
  */
class InputBlockUnclesSpec extends ErgoCorePropertyTest {

  private val uncleIdsGen: Gen[Seq[ModifierId]] =
    Gen.choose(0, InputBlockUncles.MaxUncles).flatMap(n => Gen.listOfN(n, modifierIdGen))

  private def announcement(header: org.ergoplatform.modifiers.history.header.Header,
                           digest: Digest32,
                           committed: Seq[ModifierId],
                           announced: Seq[ModifierId]): InputBlockAnnouncement = {
    val uncleField = Some(committed.map(idToBytes))
    val ext = InputBlockFields.toExtensionFields(None, digest, digest, uncleField)
    val fields = new InputBlockFields(None, digest, digest, ext.proofForInputBlockData.get, uncleField)
    InputBlockAnnouncement(InputBlockUncles.UnclesMessageVersion, header.copy(extensionRoot = ext.digest), fields, None,
      InputBlockUncles.announcementBytes(announced))
  }

  property("uncles field value and announcement bytes round trip") {
    forAll(uncleIdsGen) { ids =>
      InputBlockUncles.parseFieldValue(InputBlockUncles.fieldValue(ids.map(idToBytes))) shouldBe Some(ids)
      InputBlockUncles.parseAnnouncementBytes(InputBlockUncles.announcementBytes(ids)) shouldBe Some(ids)
      // bytes after the uncles are left to future fields
      InputBlockUncles.parseAnnouncementBytes(InputBlockUncles.announcementBytes(ids) ++ Array[Byte](1, 2)) shouldBe Some(ids)
      InputBlockUncles.fieldValue(ids.map(idToBytes)).length should be <= Extension.FieldValueMaxSize
    }
  }

  property("malformed uncles field or announcement bytes are rejected") {
    InputBlockUncles.parseFieldValue(Array.fill(31)(1.toByte)) shouldBe None
    InputBlockUncles.parseFieldValue(Array.fill(96)(1.toByte)) shouldBe None
    InputBlockUncles.parseAnnouncementBytes(Array.emptyByteArray) shouldBe None
    InputBlockUncles.parseAnnouncementBytes(Array(3.toByte) ++ Array.fill(96)(1.toByte)) shouldBe None
    InputBlockUncles.parseAnnouncementBytes(Array(2.toByte) ++ Array.fill(40)(1.toByte)) shouldBe None
    InputBlockUncles.parseAnnouncementBytes(Array(0xAB.toByte)) shouldBe None
  }

  property("version 2 announcement repeats committed uncles and survives serialization") {
    forAll(invalidHeaderGen, digest32Gen, uncleIdsGen) { (header, digest, ids) =>
      val ib = announcement(header, digest, ids, ids)
      ib.uncleIdsOpt shouldBe Some(ids)
      ib.unclesCommitted shouldBe true
      ib.merkleProof.valid(ib.header.extensionRoot) shouldBe true

      val recovered = InputBlockAnnouncement.serializer.parseBytes(InputBlockAnnouncement.serializer.toBytes(ib))
      recovered.uncleIdsOpt shouldBe Some(ids)
      recovered.unclesCommitted shouldBe true
    }
  }

  property("announced uncles other than the committed ones are not committed") {
    forAll(invalidHeaderGen, digest32Gen) { (header, digest) =>
      val committed = Seq(bytesToId(Array.fill(32)(1.toByte)))
      announcement(header, digest, committed, Seq.empty).unclesCommitted shouldBe false
      announcement(header, digest, Seq.empty, committed).unclesCommitted shouldBe false
      announcement(header, digest, committed, committed ++ committed.map(id => bytesToId(idToBytes(id).reverse)))
        .unclesCommitted shouldBe false
    }
  }

  property("version 1 announcement has no uncles field") {
    forAll(invalidHeaderGen, digest32Gen) { (header, digest) =>
      val ext = InputBlockFields.toExtensionFields(None, digest, digest)
      val fields = new InputBlockFields(None, digest, digest, ext.proofForInputBlockData.get)
      val ib = InputBlockAnnouncement(InputBlockAnnouncement.initialMessageVersion, header, fields, None)
      ib.uncleIdsOpt shouldBe None
      ib.uncleIds shouldBe Seq.empty
      ib.unclesCommitted shouldBe false
    }
  }

  property("input block fields proof is unchanged when the extension has no uncles field") {
    forAll(digest32Gen, digest32Gen) { (digest, prevDigest) =>
      val prev = Some(Array.fill(32)(5.toByte))
      val ext = InputBlockFields.toExtensionFields(prev, digest, prevDigest)
      ext.fields.exists(_._1.sameElements(Extension.InputBlockUnclesKey)) shouldBe false
      val proof = ext.proofForInputBlockData.get
      val legacy = ExtensionCandidate(ext.fields).batchProofFor(Extension.InputBlockKeys: _*).get
      proof.indices.map(_._1) shouldBe legacy.indices.map(_._1)
      proof.indices.map(_._2.toSeq) shouldBe legacy.indices.map(_._2.toSeq)

      // with the field, its leaf is proven too
      val withUncles = InputBlockFields.toExtensionFields(prev, digest, prevDigest, Some(Seq.empty))
      withUncles.proofForInputBlockData.get.indices.length shouldBe legacy.indices.length + 1
      InputBlockUncles.fromExtensionFields(withUncles.fields) shouldBe Some(Seq.empty)
    }
  }

}
