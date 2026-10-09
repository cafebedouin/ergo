package org.ergoplatform.subblocks

import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.settings.{Algos, Constants}
import scorex.crypto.authds.LeafData
import scorex.crypto.authds.merkle.Leaf
import scorex.crypto.hash.Digest32
import scorex.util.{ModifierId, bytesToId, idToBytes}

/**
  * Constants and encodings for header-level input-block uncles: PoW-valid siblings (input blocks of the same
  * ordering block whose parent is on the chain of a later input block) referenced by that later block for credit.
  * Only the reference counts: an uncle's transactions are not executed or collected.
  *
  * Where references live:
  *  - extension field `Extension.InputBlockUnclesKey` (0x03 0x03): concatenation of up to `MaxUncles` 32-byte
  *    ids. The extension root committed by the header covers it.
  *  - input block announcement (message version `UnclesMessageVersion`): the bytes after the version 1 fields
  *    (`unparsedBytes`) start with the number of uncles followed by their ids. Bytes after that are left for
  *    future fields. The batch Merkle proof of the announcement includes the uncles leaf, so the ids it repeats
  *    are checked against the extension root.
  */
object InputBlockUncles {

  /** At most this many uncles per input block. */
  val MaxUncles: Int = 2

  /** Input block announcement version carrying uncle ids. */
  val UnclesMessageVersion: Byte = 2.toByte

  /**
    * The field rule shared by the generator and the validator: at most `MaxUncles` ids, no duplicate, and no
    * reference of the block to itself.
    *
    * @param selfId - id of the referencing block
    * @return why the field is not well-formed, None if it is
    */
  def fieldViolation(selfId: ModifierId, ids: Seq[ModifierId]): Option[String] = {
    if (ids.length > MaxUncles) {
      Some(s"${ids.length} uncles, at most $MaxUncles allowed")
    } else if (ids.distinct.length != ids.length) {
      Some("duplicate uncle id")
    } else if (ids.contains(selfId)) {
      Some("block references itself")
    } else {
      None
    }
  }

  /** Extension field value for the uncle ids given. */
  def fieldValue(ids: Seq[Array[Byte]]): Array[Byte] = Array.concat(ids: _*)

  /** Uncle ids from an extension field value, None if the value is malformed. */
  def parseFieldValue(value: Array[Byte]): Option[Seq[ModifierId]] = {
    val idSize = Constants.ModifierIdSize
    if (value.length % idSize != 0 || value.length / idSize > MaxUncles) {
      None
    } else {
      Some(value.grouped(idSize).map(bytesToId).toList)
    }
  }

  /** Uncle ids from extension fields: None if the field is absent or malformed. */
  def fromExtensionFields(fields: Seq[(Array[Byte], Array[Byte])]): Option[Seq[ModifierId]] = {
    fields.find(_._1.sameElements(Extension.InputBlockUnclesKey)).flatMap(kv => parseFieldValue(kv._2))
  }

  /** Bytes appended to a version 2 announcement: number of uncles followed by their ids. */
  def announcementBytes(ids: Seq[ModifierId]): Array[Byte] = {
    Array(ids.length.toByte) ++ fieldValue(ids.map(idToBytes))
  }

  /** Uncle ids from the bytes appended to a version 2 announcement, None if malformed. */
  def parseAnnouncementBytes(bytes: Array[Byte]): Option[Seq[ModifierId]] = {
    if (bytes.isEmpty) {
      None
    } else {
      val n = bytes(0).toInt
      val idSize = Constants.ModifierIdSize
      if (n < 0 || n > MaxUncles || bytes.length < 1 + n * idSize) {
        None
      } else {
        parseFieldValue(bytes.slice(1, 1 + n * idSize))
      }
    }
  }

  /** Hash of the extension Merkle tree leaf holding the uncle ids given. */
  def leafHash(ids: Seq[ModifierId]): Digest32 = {
    val leaf = Extension.kvToLeaf(Extension.InputBlockUnclesKey -> fieldValue(ids.map(idToBytes)))
    Leaf[Digest32](LeafData @@ leaf)(Algos.hash).hash
  }

}
