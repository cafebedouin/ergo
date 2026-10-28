package org.ergoplatform.http.routes

import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.StatusReply
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.http.api.MiningApiRoute
import org.ergoplatform.http.api.requests.MiningRequest
import org.ergoplatform.mining.CandidateGenerator.{Candidate, GenerateCandidate}
import org.ergoplatform.mining.{AutolykosSolution, ProofOfUpcomingTransactions}
import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.mempool.TransactionMembershipProof
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.Stubs
import org.ergoplatform.utils.generators.ErgoCoreGenerators.{defaultHeaderGen, genECPoint}
import org.ergoplatform.utils.generators.ErgoNodeTransactionGenerators.invalidErgoTransactionGen
import org.ergoplatform.{ErgoTreePredef, Pay2SAddress}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.util.bytesToId

import scala.util.Try

class MiningApiRouteSpec
  extends AnyFlatSpec
    with Matchers
    with ScalatestRouteTest
    with Stubs
    with FailFastCirceSupport {

  import org.ergoplatform.utils.ErgoNodeTestConstants._

  val prefix = "/mining"

  val localSetting: ErgoSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(useExternalMiner = true))
  val route: Route = MiningApiRoute(minerRef, localSetting).route

  val settingsWithAuth: ErgoSettings = localSetting.copy(
    scorexSettings = localSetting.scorexSettings.copy(
      restApi = localSetting.scorexSettings.restApi.copy(
        apiKeyHash = Some("e1c7ef7b3b742c5ae8f52c24d2c5f5c5dccd9c23a41fa6e76e5a6b62c8f72a10")
      )
    )
  )
  val routeWithAuth: Route = MiningApiRoute(minerRef, settingsWithAuth).route

  val solution = AutolykosSolution(genECPoint.sample.get, genECPoint.sample.get, Array.fill(32)(9: Byte), BigInt(0))

  // Valid compressed public key hex (33 bytes = 66 hex chars) - using a valid secp256k1 point
  val validPkHex = "020000000000000000000000000000000000000000000000000000000000000001"

  it should "return requested candidate" in {
    Get(prefix + "/candidate") ~> route ~> check {
      status shouldBe StatusCodes.OK
      Try(responseAs[Json]) shouldBe 'success
    }
  }

  it should "process external solution" in {
    Post(prefix + "/solution", solution.asJson) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "display miner pk" in {
    Get(prefix + "/rewardAddress") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val script = ErgoTreePredef.rewardOutputScript(settings.chainSettings.monetary.minerRewardDelay, pk)
      val addressStr = Pay2SAddress(script)(settings.addressEncoder).toString()
      responseAs[Json].hcursor.downField("rewardAddress").as[String] shouldEqual Right(addressStr)
    }
  }

  it should "return candidate with valid custom miner public key" in {
    val request = MiningRequest(Seq.empty, validPkHex)

    Post(prefix + "/candidateWithTxsAndPk", request.asJson) ~> route ~> check {
      status shouldBe StatusCodes.OK
      Try(responseAs[Json]) shouldBe 'success
    }
  }

  it should "report what became of each requested transaction, in request order" in {
    val Seq(proved, refused, notProved) = Seq.fill(3)(invalidErgoTransactionGen.sample.get)
    val blockTxs = BlockTransactions(bytesToId(Array.fill(32)(0: Byte)), Header.InitialVersion, Seq(proved, notProved))
    val proofs = ProofOfUpcomingTransactions(defaultHeaderGen.sample.get,
      Seq(TransactionMembershipProof(proved.id, blockTxs.proofFor(proved.id).get)))
    val candidate = Candidate(null, externalWorkMessage.copy(proofsForMandatoryTransactions = Some(proofs)),
      Seq(proved, notProved))
    val generator = system.actorOf(Props(new Actor {
      def receive: Receive = { case _: GenerateCandidate => sender() ! StatusReply.success(candidate) }
    }))
    val statusRoute = MiningApiRoute(generator, localSetting).route
    val requested = Seq(proved, refused, notProved)
    val expected = Json.arr(
      Json.obj("id" -> Json.fromString(proved.id), "status" -> "proved".asJson),
      Json.obj("id" -> Json.fromString(refused.id), "status" -> "refused".asJson),
      Json.obj("id" -> Json.fromString(notProved.id), "status" -> "notProved".asJson))

    Post(prefix + "/candidateWithTxs", requested.asJson) ~> statusRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("txStatus").focus shouldBe Some(expected)
      responseAs[Json].hcursor.downField("proof").focus shouldBe defined
    }
    Post(prefix + "/candidateWithTxsAndPk", MiningRequest(requested, validPkHex).asJson) ~> statusRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("txStatus").focus shouldBe Some(expected)
    }
    // nothing requested, nothing to report
    Get(prefix + "/candidate") ~> statusRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("txStatus").focus shouldBe None
    }
  }

  it should "return candidate without api_key when auth is enabled" in {
    Get(prefix + "/candidate") ~> routeWithAuth ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "return reward address without api_key when auth is enabled" in {
    Get(prefix + "/rewardAddress") ~> routeWithAuth ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "return reward public key without api_key when auth is enabled" in {
    Get(prefix + "/rewardPublicKey") ~> routeWithAuth ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "accept solution without api_key when auth is enabled" in {
    Post(prefix + "/solution", solution.asJson) ~> routeWithAuth ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "reject candidateWithTxs without api_key when auth is enabled" in {
    Post(prefix + "/candidateWithTxs", Json.arr()) ~> routeWithAuth ~> check {
      rejection shouldBe AuthorizationFailedRejection
    }
  }

  it should "encode and decode MiningRequest correctly" in {
    val request = MiningRequest(Seq.empty, validPkHex)

    val json = request.asJson
    val decodedTxs = json.hcursor.downField("txs").as[Seq[Json]]
    val decodedPk = json.hcursor.downField("pk").as[String]

    decodedTxs shouldBe 'right
    decodedPk shouldBe Right(validPkHex)
  }

}
