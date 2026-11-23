package org.ergoplatform.mining

import org.ergoplatform.ErgoBox
import org.ergoplatform.mining.emission.EmissionRules.CoinsInOneErgo
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.state.{ErgoStateContext, VotingData}
import org.ergoplatform.settings.{ChainSettings, ChainSettingsReader, ErgoValidationSettingsUpdate, Parameters}
import org.ergoplatform.utils.ErgoTestHelpers
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.util.bytesToId
import sigma.Colls
import sigma.data.{Digest32Coll, ProveDlog}

import scala.util.{Success, Try}

/**
  * The emission transaction `CandidateGenerator.collectRewards` builds in the last emission epoch (heights 2,016,000
  * to 2,080,799, 3 ERG per block, re-emission 0), on mainnet settings, validated with
  * `ErgoTransaction.statefulValidity`.
  *
  * Box values assume every block spends the emission box: starting from the box created at height 1,923,277
  * (835,446 ERG, 6,663,113,000,000,000 re-emission tokens), it holds `948 + 3 * (2,080,800 - h)` ERG and
  * 6,301,181,000,000,000 re-emission tokens before the block at height `h` of the last epoch.
  */
class EmissionEndSpec extends AnyFlatSpec with Matchers with ErgoTestHelpers {

  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.generators.ErgoCoreGenerators._

  private val mainnet: ChainSettings = ChainSettingsReader.read("src/main/resources/mainnet.conf").get
  // a mainnet node with `mining = true` must have `checkReemissionRules = true`; the default is false
  private val mainnetEip27: ChainSettings =
    mainnet.copy(reemission = mainnet.reemission.copy(checkReemissionRules = true))
  private val reemission = mainnet.reemission

  private val v4Params = new Parameters(0,
    Parameters.DefaultParameters.updated(Parameters.BlockVersion, Header.Interpreter60Version),
    ErgoValidationSettingsUpdate.empty)
  private implicit val verifier: ErgoInterpreter = ErgoInterpreter(v4Params)

  private val pk: ProveDlog = defaultMinerPk
  private val Erg = CoinsInOneErgo
  private val LastEpochStart = 2016000
  private val LastEmissionHeight = 2080799
  private val ReemissionTokensInLastEpoch = 6301181L * Erg

  private val emissionNft = Digest32Coll @@ reemission.emissionNftIdBytes
  private val reemissionToken = Digest32Coll @@ reemission.reemissionTokenIdBytes
  private val parentTemplate = defaultHeaderGen.sample.get

  /** Context of the block at height `h`, as `CandidateGenerator` builds it: `upcoming` over a header at `h - 1`. */
  private def contextAt(h: Int, cs: ChainSettings): ErgoStateContext = {
    val parent = parentTemplate.copy(height = h - 1, version = Header.Interpreter60Version)
    val base = new ErgoStateContext(Seq(parent), None, genesisStateDigest, v4Params, validationSettingsNoIl,
      VotingData.empty)(cs)
    base.upcoming(pk.value, parent.timestamp + 1, parent.nBits, Array.fill(3)(0.toByte),
      ErgoValidationSettingsUpdate.empty, Header.Interpreter60Version)
  }

  private def emissionBox(value: Long, reemissionTokens: Long, creationHeight: Int): ErgoBox =
    new ErgoBox(value, mainnet.monetary.emissionBoxProposition,
      Colls.fromItems(emissionNft -> 1L, reemissionToken -> reemissionTokens), Map.empty,
      bytesToId(Array.fill(32)(1.toByte)), 0, creationHeight)

  /** The emission box before the block at height `h` of the last emission epoch. */
  private def lastEpochEmissionBox(h: Int): ErgoBox =
    emissionBox((948L + 3L * (LastEmissionHeight + 1 - h)) * Erg, ReemissionTokensInLastEpoch, h - 1)

  private def emissionTx(box: ErgoBox, h: Int, cs: ChainSettings): ErgoTransaction =
    CandidateGenerator.collectRewards(Some(box), h - 1, Seq.empty, pk, contextAt(h, cs)).head

  private def validity(tx: ErgoTransaction, box: ErgoBox, h: Int, cs: ChainSettings): Try[Int] =
    tx.statefulValidity(IndexedSeq(box), IndexedSeq.empty, contextAt(h, cs))

  private def reemissionTokensOf(box: ErgoBox): Long =
    box.additionalTokens.toArray.collectFirst { case (id, amount) if id == reemissionToken => amount }.getOrElse(0L)

  /**
    * Builds and validates the emission transaction at each height of `heights`, spending the previous one's
    * emission output, and returns the emission box left after the last height.
    */
  private def spendThrough(start: ErgoBox, heights: Range, cs: ChainSettings): ErgoBox =
    heights.foldLeft(start) { (box, h) =>
      val tx = emissionTx(box, h, cs)
      withClue(s"height $h, emission box of ${box.value} nanoERG: ") {
        validity(tx, box, h, cs) shouldBe a[Success[_]]
      }
      tx.outputs(0)
    }

  /** Two blocks of 6 ERG emission (3 ERG of it as re-emission tokens) before the last epoch, then three blocks in it. */
  private def acrossLastEpochStart(cs: ChainSettings): Unit = {
    val start = emissionBox(lastEpochEmissionBox(LastEpochStart).value + 12 * Erg,
      ReemissionTokensInLastEpoch + 6 * Erg, LastEpochStart - 3)
    val atEpochStart = spendThrough(start, (LastEpochStart - 2) until LastEpochStart, cs)
    atEpochStart.value shouldBe lastEpochEmissionBox(LastEpochStart).value
    reemissionTokensOf(atEpochStart) shouldBe ReemissionTokensInLastEpoch

    // re-emission is 0 from here on: the miner's output holds no re-emission token, not a token with amount 0
    emissionTx(atEpochStart, LastEpochStart, cs).outputs(1).additionalTokens.toArray shouldBe empty

    val after = spendThrough(atEpochStart, LastEpochStart to (LastEpochStart + 2), cs)
    after.value shouldBe lastEpochEmissionBox(LastEpochStart + 3).value
    reemissionTokensOf(after) shouldBe ReemissionTokensInLastEpoch
  }

  "The miner's emission transaction" should "be valid across the start of the last emission epoch" in {
    acrossLastEpochStart(mainnet)
  }

  it should "be valid across the start of the last emission epoch with EIP-27 rules checked" in {
    acrossLastEpochStart(mainnetEip27)
  }

  it should "be valid up to the last emission height" in {
    val left = spendThrough(lastEpochEmissionBox(LastEmissionHeight - 2),
      (LastEmissionHeight - 2) to LastEmissionHeight, mainnet)
    left.value shouldBe 948L * Erg
    reemissionTokensOf(left) shouldBe ReemissionTokensInLastEpoch
  }
}
