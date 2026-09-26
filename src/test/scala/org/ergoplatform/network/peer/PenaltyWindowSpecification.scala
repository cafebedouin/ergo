package org.ergoplatform.network.peer

import java.net.InetSocketAddress

import org.ergoplatform.utils.ErgoCorePropertyTest
import org.ergoplatform.utils.ErgoNodeTestConstants.initSettings

import scala.concurrent.duration._

/**
  * `penaltySafeInterval` limits a peer to one counted penalty per interval. A peer penalized more often than that
  * must still accumulate one penalty per interval, or a peer that misbehaves continuously never reaches the ban
  * threshold.
  */
class PenaltyWindowSpecification extends ErgoCorePropertyTest {

  private val window = 200.millis

  private def database(): PeerDatabase = {
    val network = initSettings.scorexSettings.network.copy(penaltySafeInterval = window)
    new PeerDatabase(initSettings.copy(scorexSettings = initSettings.scorexSettings.copy(network = network)))
  }

  private val peer = new InetSocketAddress("10.0.0.1", 9020)
  private val spam = PenaltyType.SpamPenalty.penaltyScore

  property("a peer penalized more often than the safe interval accumulates one penalty per interval") {
    val db = database()
    val end = System.currentTimeMillis() + 10 * window.toMillis
    while (System.currentTimeMillis() < end) {
      db.penalize(peer, PenaltyType.SpamPenalty)
      Thread.sleep(window.toMillis / 4)
    }
    withClue(s"score after ten safe intervals of penalties every ${window.toMillis / 4} ms: ${db.penaltyScore(peer)}: ") {
      db.penaltyScore(peer) should be > 5 * spam
    }
  }

  property("a peer penalized once per safe interval accumulates every penalty (control)") {
    val db = database()
    (1 to 4).foreach { _ =>
      db.penalize(peer, PenaltyType.SpamPenalty)
      Thread.sleep(window.toMillis + 50)
    }
    db.penaltyScore(peer) shouldBe 4 * spam
  }
}
