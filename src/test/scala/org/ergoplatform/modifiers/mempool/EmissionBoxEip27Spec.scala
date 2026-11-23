package org.ergoplatform.modifiers.mempool

import org.ergoplatform.mining.emission.EmissionRules.CoinsInOneErgo
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.state.{ErgoStateContext, VotingData}
import org.ergoplatform.settings.{ChainSettings, ChainSettingsReader, ErgoValidationSettingsUpdate, Parameters}
import org.ergoplatform.utils.ErgoTestHelpers
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoTreePredef, Input}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.util.bytesToId
import sigma.Colls
import sigma.ast.ErgoTree
import sigma.data.TrivialProp.TrueProp
import sigma.data.{Digest32Coll, ProveDlog}
import sigma.interpreter.ProverResult

import scala.util.{Failure, Success, Try}

/**
  * EIP-27 rules (`checkReemissionRules = true`, which a mainnet node needs to mine) on spends of the emission box in
  * the last emission epoch (heights 2,016,000 to 2,080,799, 3 ERG per block, re-emission 0), on mainnet settings.
  *
  * Box values assume every block spends the emission box: starting from the box created at height 1,923,277
  * (835,446 ERG, 6,663,113,000,000,000 re-emission tokens), it holds `948 + 3 * (2,080,800 - h)` ERG and
  * 6,301,181,000,000,000 re-emission tokens before the block at height `h`, so it is first worth 100,000 ERG or
  * less before the block at 2,047,783.
  */
class EmissionBoxEip27Spec extends AnyFlatSpec with Matchers with ErgoTestHelpers {

  import org.ergoplatform.utils.ErgoCoreTestConstants._
  import org.ergoplatform.utils.generators.ErgoCoreGenerators._

  private val mainnet: ChainSettings = ChainSettingsReader.read("src/main/resources/mainnet.conf").get
  private val mainnetEip27: ChainSettings =
    mainnet.copy(reemission = mainnet.reemission.copy(checkReemissionRules = true))
  private val reemission = mainnet.reemission

  private val v4Params = new Parameters(0,
    Parameters.DefaultParameters.updated(Parameters.BlockVersion, Header.Interpreter60Version),
    ErgoValidationSettingsUpdate.empty)
  private implicit val verifier: ErgoInterpreter = ErgoInterpreter(v4Params)

  private val pk: ProveDlog = defaultMinerPk
  private val Erg = CoinsInOneErgo
  private val LastEmissionHeight = 2080799
  private val FirstHeightUnder100k = 2047783
  private val ReemissionTokens = 6301181L * Erg

  private val emissionNft = Digest32Coll @@ reemission.emissionNftIdBytes
  private val reemissionToken = Digest32Coll @@ reemission.reemissionTokenIdBytes
  private val minerTree = ErgoTreePredef.rewardOutputScript(mainnet.monetary.minerRewardDelay, pk)
  private val parentTemplate = defaultHeaderGen.sample.get

  private def contextAt(h: Int, cs: ChainSettings): ErgoStateContext = {
    val parent = parentTemplate.copy(height = h - 1, version = Header.Interpreter60Version)
    val base = new ErgoStateContext(Seq(parent), None, genesisStateDigest, v4Params, validationSettingsNoIl,
      VotingData.empty)(cs)
    base.upcoming(pk.value, parent.timestamp + 1, parent.nBits, Array.fill(3)(0.toByte),
      ErgoValidationSettingsUpdate.empty, Header.Interpreter60Version)
  }

  /** The emission box before the block at height `h` of the last emission epoch. */
  private def emissionBox(h: Int): ErgoBox =
    new ErgoBox((948L + 3L * (LastEmissionHeight + 1 - h)) * Erg, mainnet.monetary.emissionBoxProposition,
      Colls.fromItems(emissionNft -> 1L, reemissionToken -> ReemissionTokens), Map.empty,
      bytesToId(Array.fill(32)(1.toByte)), 0, h - 1)

  /** A spend of the emission box at height `h` paying 3 ERG to the miner, with the given tokens in its outputs. */
  private def emissionSpend(box: ErgoBox, h: Int, emissionOutTokens: Seq[(Digest32Coll, Long)],
                            minerOutTokens: Seq[(Digest32Coll, Long)]): ErgoTransaction =
    ErgoTransaction(IndexedSeq(new Input(box.id, ProverResult.empty)), IndexedSeq.empty, IndexedSeq(
      new ErgoBoxCandidate(box.value - 3 * Erg, box.ergoTree, h, Colls.fromItems(emissionOutTokens: _*)),
      new ErgoBoxCandidate(3 * Erg, minerTree, h, Colls.fromItems(minerOutTokens: _*))))

  /** The spend the last emission epoch calls for: the emission NFT and all re-emission tokens stay in the box. */
  private def properSpend(box: ErgoBox, h: Int): ErgoTransaction =
    emissionSpend(box, h, Seq(emissionNft -> 1L, reemissionToken -> ReemissionTokens), Seq.empty)

  private def validity(tx: ErgoTransaction, box: ErgoBox, h: Int, cs: ChainSettings): Try[Int] =
    tx.statefulValidity(IndexedSeq(box), IndexedSeq.empty, contextAt(h, cs))

  // the last height above 100,000 ERG, the first at or under it, one well under it, and the last emission height
  private val heights = Seq(FirstHeightUnder100k - 1, FirstHeightUnder100k, 2060000, LastEmissionHeight)

  "EIP-27 rules" should "accept the emission box spend whatever the box is worth" in {
    emissionBox(FirstHeightUnder100k - 1).value shouldBe 100002L * Erg
    emissionBox(FirstHeightUnder100k).value shouldBe 99999L * Erg
    emissionBox(LastEmissionHeight).value shouldBe 951L * Erg
    heights.foreach { h =>
      val box = emissionBox(h)
      withClue(s"height $h, emission box of ${box.value} nanoERG: ") {
        validity(properSpend(box, h), box, h, mainnetEip27) shouldBe a[Success[_]]
        validity(properSpend(box, h), box, h, mainnet) shouldBe a[Success[_]]
      }
    }
  }

  it should "reject an emission box spend that moves or burns its tokens, whatever the box is worth" in {
    heights.foreach { h =>
      val box = emissionBox(h)
      val tokenToMiner = emissionSpend(box, h,
        Seq(emissionNft -> 1L, reemissionToken -> (ReemissionTokens - 1)), Seq(reemissionToken -> 1L))
      val tokenBurned = emissionSpend(box, h,
        Seq(emissionNft -> 1L, reemissionToken -> (ReemissionTokens - 1)), Seq.empty)
      val nftBurned = emissionSpend(box, h, Seq(reemissionToken -> ReemissionTokens), Seq.empty)
      Seq("a re-emission token paid to the miner" -> tokenToMiner, "a re-emission token burned" -> tokenBurned,
        "the emission NFT burned" -> nftBurned).foreach { case (what, tx) =>
        withClue(s"height $h, emission box of ${box.value} nanoERG, $what: ") {
          validity(tx, box, h, mainnetEip27) shouldBe a[Failure[_]]
          // control: the emission contract does not look at tokens, so only the EIP-27 rules reject these
          validity(tx, box, h, mainnet) shouldBe a[Success[_]]
        }
      }
    }
  }

  it should "treat re-emission tokens in a box without the emission NFT as a burn" in {
    val h = 2060000
    val anyoneCanSpend: ErgoTree = ErgoTree.fromSigmaBoolean(TrueProp)
    val held = 10L * Erg
    val box = new ErgoBox(50L * Erg, anyoneCanSpend, Colls.fromItems(reemissionToken -> held), Map.empty,
      bytesToId(Array.fill(32)(2.toByte)), 0, h - 10)
    val inputs = IndexedSeq(new Input(box.id, ProverResult.empty))
    val keepsTokens = ErgoTransaction(inputs, IndexedSeq.empty, IndexedSeq(
      new ErgoBoxCandidate(box.value, anyoneCanSpend, h, Colls.fromItems(reemissionToken -> held))))
    val paysToReemission = ErgoTransaction(inputs, IndexedSeq.empty, IndexedSeq(
      new ErgoBoxCandidate(held, reemission.reemissionRules.payToReemission, h),
      new ErgoBoxCandidate(box.value - held, anyoneCanSpend, h)))

    validity(keepsTokens, box, h, mainnetEip27) shouldBe a[Failure[_]]
    validity(paysToReemission, box, h, mainnetEip27) shouldBe a[Success[_]]
  }
}
