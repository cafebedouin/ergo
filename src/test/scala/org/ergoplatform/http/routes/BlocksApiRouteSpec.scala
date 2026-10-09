package org.ergoplatform.http.routes

import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes, UniversalEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.http.api.BlocksApiRoute
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.ErgoReadersHolder.GetDataFromHistory
import org.ergoplatform.settings.Algos
import org.ergoplatform.utils.{InputBlockUnclesTestHelpers, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.util.ModifierId

class BlocksApiRouteSpec
  extends AnyFlatSpec
  with Matchers
  with ScalatestRouteTest
  with FailFastCirceSupport
  with Stubs {

  import org.ergoplatform.utils.ErgoNodeTestConstants._
  import org.ergoplatform.utils.generators.ValidBlocksGenerators._

  val prefix = "/blocks"

  val route: Route = BlocksApiRoute(nodeViewRef, digestReadersRef, settings).route

  val headerIdBytes: ModifierId = history.lastHeaders(1).headers.head.id
  val headerIdString: String    = Algos.encode(headerIdBytes)

  it should "get last blocks" in {
    Get(prefix) ~> route ~> check {
      status shouldBe StatusCodes.OK
      history
        .headerIdsAt(0, 50)
        .map(Algos.encode)
        .asJson shouldEqual responseAs[Json]
    }
  }

  it should "post block correctly" in {
    val (st, bh)             = createUtxoState(settings)
    val block: ErgoFullBlock = validFullBlock(parentOpt = None, st, bh)
    val blockJson: UniversalEntity =
      HttpEntity(block.asJson.toString).withContentType(ContentTypes.`application/json`)
    Post(prefix, blockJson) ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "get last headers" in {
    Get(prefix + "/lastHeaders/1") ~> route ~> check {
      status shouldBe StatusCodes.OK
      history
        .lastHeaders(1)
        .headers
        .map(_.asJson)
        .asJson shouldEqual responseAs[Json]
    }
  }

  it should "get block at height" in {
    Get(prefix + "/at/0") ~> route ~> check {
      status shouldBe StatusCodes.OK
      history
        .headerIdsAtHeight(0)
        .map(Algos.encode)
        .asJson shouldEqual responseAs[Json]
    }
  }

  it should "get chain slice" in {
    Get(prefix + "/chainSlice?fromHeight=0") ~> route ~> check {
      status shouldBe StatusCodes.OK
      chain.map(_.header).asJson shouldEqual responseAs[Json]
    }
    Get(prefix + "/chainSlice?fromHeight=2&toHeight=4") ~> route ~> check {
      status shouldBe StatusCodes.OK
      chain.slice(2, 4).map(_.header).asJson shouldEqual responseAs[Json]
    }
  }

  it should "reject chain slice ranges above the maximum headers limit" in {
    Get(prefix + "/chainSlice?fromHeight=0&toHeight=16385") ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "get block by header id" in {
    Get(prefix + "/" + headerIdString) ~> route ~> check {
      status shouldBe StatusCodes.OK
      val expected = history
        .typedModifierById[Header](headerIdBytes)
        .flatMap(history.getFullBlock)
        .map(_.asJson)
        .get

      responseAs[Json] shouldEqual expected
    }
  }

  it should "get blocks by header ids" in {
    val headerIdsBytes               = history.lastHeaders(10).headers
    val headerIdsString: Seq[String] = headerIdsBytes.map(h => Algos.encode(h.id))

    Post(prefix + "/headerIds", headerIdsString.asJson) ~> route ~> check {
      status shouldBe StatusCodes.OK

      val expected = headerIdsBytes
        .map(_.id)
        .flatMap(headerId =>
          history.typedModifierById[Header](headerId).flatMap(history.getFullBlock)
        )

      responseAs[Seq[ErgoFullBlock]] shouldEqual expected
    }
  }

  it should "get header by header id" in {
    Get(prefix + "/" + headerIdString + "/header") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val expected = history
        .typedModifierById[Header](headerIdBytes)
        .flatMap(history.getFullBlock)
        .map(_.header.asJson)
        .get

      responseAs[Json] shouldEqual expected
    }
  }

  it should "get transactions by header id" in {
    Get(prefix + "/" + headerIdString + "/transactions") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val header    = history.typedModifierById[Header](headerIdBytes).value
      val fullBlock = history.getFullBlock(header).value
      val expected  = fullBlock.blockTransactions.asJson
      responseAs[Json] shouldEqual expected
    }
  }

  it should "report credited uncles of input blocks only with input-block uncles enabled" in {
    val unclesSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(inputBlockUncles = true))
    val unclesRoute = BlocksApiRoute(nodeViewRef, digestReadersRef, unclesSettings).route
    Get(prefix + "/bestInputBlock") ~> unclesRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("creditedUncles").as[Seq[String]] shouldBe Right(Seq.empty)
    }
    Get(prefix + "/bestInputChain") ~> unclesRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("creditedUncles").focus.isDefined shouldBe true
    }
    // flag off: the base response
    Get(prefix + "/bestInputBlock") ~> route ~> check {
      responseAs[Json].hcursor.downField("creditedUncles").focus shouldBe None
    }
    Get(prefix + "/bestInputChain") ~> route ~> check {
      responseAs[Json].hcursor.downField("creditedUncles").focus shouldBe None
    }
  }

  it should "report a credited uncle in /blocks/bestInputChain and /blocks/bestInputBlock" in {
    object helpers extends InputBlockUnclesTestHelpers with Matchers
    // flag-on history: chain A <- B, sibling S (child of A), C (child of B) referencing S
    val (h, us) = helpers.setup()
    val (_, b, s) = helpers.chainWithSibling(h, us, Seq(helpers.spend(helpers.boxes(2))))
    val cTxs = Seq(helpers.spend(helpers.boxes(3)))
    val c = helpers.announce(h, us, Some(b.id), cTxs, uncles = Seq(s.id))
    helpers.process(h, us, c, cTxs)._1 shouldBe Seq(c.id)
    h.getCreditedUncles(c.id) shouldBe Seq(s.id)

    val readers = system.actorOf(Props(new Actor {
      def receive: Receive = {
        case GetDataFromHistory(f) => sender() ! f(h)
      }
    }))
    val unclesSettings = settings.copy(nodeSettings = settings.nodeSettings.copy(inputBlockUncles = true))
    val unclesRoute = BlocksApiRoute(nodeViewRef, readers, unclesSettings).route

    Get(prefix + "/bestInputChain") ~> unclesRoute ~> check {
      status shouldBe StatusCodes.OK
      val credited = responseAs[Json].hcursor.downField("creditedUncles")
      credited.downField(c.id).as[Seq[String]] shouldBe Right(Seq(s.id))
      credited.downField(b.id).as[Seq[String]] shouldBe Right(Seq.empty)
    }
    Get(prefix + "/bestInputBlock") ~> unclesRoute ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Json].hcursor.downField("bestInputBlock").as[String] shouldBe Right(c.id)
      responseAs[Json].hcursor.downField("creditedUncles").as[Seq[String]] shouldBe Right(Seq(s.id))
    }
  }

}
