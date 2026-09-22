package org.ergoplatform.it.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** No containers: the classifier is a pure function of sampled state. */
class ConvergenceDiagnosisSpec extends AnyFlatSpec with Matchers {
  import ConvergenceDiagnosis._

  private def node(name: String, headers: Int, full: Int, headerId: String, fullId: String,
                   peers: Int = 1, mining: Boolean = false, state: String = NodeSample.Running,
                   at: Long = 0L): NodeSample =
    NodeSample(name, at, state, answered = true, Some(headers), Some(full), Some(headerId), Some(fullId), Some(peers), Some(mining))

  private def silent(name: String, state: String = NodeSample.Running, at: Long = 0L): NodeSample =
    NodeSample(name, at, state, answered = false, None, None, None, None, None, None)

  /** The same round seen `rounds` times, ten seconds apart (so the 30 s lag/progress spans are covered): nothing moved. */
  private def frozen(round: Seq[NodeSample], rounds: Int = 5): Seq[Seq[NodeSample]] =
    (0 until rounds).map(i => round.map(_.copy(atMillis = i * 10000L)))

  // End states below marked "CI" are the ones upstream's own runs ended in (run ids in the comments).

  "A frozen pair" should "be named a tie when heights are equal and tips differ, without adjudicating" in {
    // CI 35356023528, 35522217646, 35522895591 (DeepRollBackSpec): A 231/231, B 231/231, different tips
    val d = diagnose(frozen(Seq(node("A", 231, 231, "09edb3f9", "09edb3f9"), node("B", 231, 231, "c718c331", "c718c331"))))
    d.cause shouldBe EqualHeightTie
    d.evidence should include("mining=off")
    d.evidence should not include "not a node defect"
  }

  it should "not call a one-block pipeline gap headers-ahead" in {
    // CI 34750207083 startup row: A 20/20, B headers 20 / full 8 is headers-ahead; a gap of 1 is not
    diagnose(frozen(Seq(node("A", 20, 20, "aa", "aa"), node("B", 20, 8, "aa", "b8")))).cause shouldBe HeadersAheadFullStuck
    diagnose(frozen(Seq(node("A", 20, 20, "aa", "aa"), node("B", 20, 19, "aa", "b19")))).cause should not be HeadersAheadFullStuck
  }

  it should "be named headers-ahead when full blocks stay behind the headers" in {
    // CI 35579493804 (DeepRollBackSpec): B has A's 260 headers, full height stays at 70 on its own fork
    val d = diagnose(frozen(Seq(node("A", 260, 260, "a377fa9e", "a377fa9e"), node("B", 260, 70, "a377fa9e", "19dd9a76"))))
    d.cause shouldBe HeadersAheadFullStuck
    d.evidence should include("B[running] headers=260:a377fa9e full=70:19dd9a76")
  }

  it should "be named lighter-fork-not-switching when the lower node never took the higher headers" in {
    // CI 34744409218, 34745269264 (DeepRollBackSpec): A 249/249, B 70/70, one peer each
    val d = diagnose(frozen(Seq(node("A", 249, 249, "c1c00bf0", "c1c00bf0"), node("B", 70, 70, "a8eae678", "a8eae678"))))
    d.cause shouldBe LighterForkNotSwitching
    d.evidence should include("B[running]")
  }

  it should "be named a stalled chain when tips agree" in {
    diagnose(frozen(Seq(node("A", 12, 12, "aa", "aa"), node("B", 12, 12, "aa", "aa")))).cause shouldBe ChainStalled
  }

  "Infrastructure causes" should "win over chain causes" in {
    val tie = Seq(node("A", 5, 5, "aa", "aa"), node("B", 5, 5, "bb", "bb"))
    diagnose(frozen(tie.updated(1, silent("B", "exited(137)")))).cause shouldBe NodeDown
    // one unanswered `docker inspect` proves nothing
    diagnose(frozen(tie, 4) :+ tie.updated(1, silent("B", NodeSample.Unknown, at = 40000L))).cause should not be NodeDown
    diagnose(frozen(tie.updated(1, silent("B", "oom-killed")))).evidence should include("oom-killed")
    diagnose(frozen(tie.updated(1, silent("B")))).cause shouldBe NodeUnresponsive
    diagnose(frozen(tie.updated(1, node("B", 5, 5, "bb", "bb", peers = 0)))).cause shouldBe NoPeers
  }

  "A phase that isolates nodes on purpose" should "not name NO_PEERS" in {
    val isolated = Seq(node("A", 5, 5, "aa", "aa", peers = 0), node("B", 5, 5, "aa", "aa", peers = 0))
    diagnose(frozen(isolated)).cause shouldBe NoPeers
    diagnose(frozen(isolated), expectPeers = false).cause shouldBe ChainStalled
  }

  "Full blocks stuck while headers advance" should "be named headers-ahead, not lagging or progressing" in {
    // B takes A's headers but its full height never moves: the live form of CI 35579493804
    val history = (0 to 40).map { i =>
      Seq(node("A", 100 + i, 100 + i, s"a$i", s"a$i", at = i * 1000L), node("B", 100 + i, 70, s"a$i", "b70", at = i * 1000L))
    }
    diagnose(history).cause shouldBe HeadersAheadFullStuck
  }

  "Progress early in a long wait" should "not hide a later stall" in {
    val early = (0 to 3).map(i => Seq(node("A", 10 + i, 10 + i, s"a$i", s"a$i", at = i * 1000L), node("B", 10 + i, 10 + i, s"a$i", s"a$i", at = i * 1000L)))
    val stalled = (4 to 60).map(i => Seq(node("A", 13, 13, "a3", "a3", at = i * 1000L), node("B", 13, 13, "a3", "a3", at = i * 1000L)))
    diagnose(early ++ stalled).cause shouldBe ChainStalled
  }

  "A node that fell silent" should "not count as negative progress" in {
    val history = (0 to 40).map { i =>
      Seq(node("A", 10 + i, 10 + i, s"a$i", s"a$i", at = i * 1000L), if (i < 35) node("B", 10 + i, 10 + i, s"a$i", s"a$i", at = i * 1000L) else silent("B", at = i * 1000L))
    }
    // the classifier names the silence; and had it judged progress, the gain over nodes present in both
    // rounds is positive (a silent node is not a negative gain)
    diagnose(history).cause shouldBe NodeUnresponsive
    val bothPresent = history.take(35)
    val d = diagnose(bothPresent.takeRight(31))
    d.cause shouldBe StillProgressing
    d.evidence should not include "(-"
  }

  "A paused container" should "be named unresponsive, not down" in {
    val d = diagnose(frozen(Seq(node("A", 5, 5, "aa", "aa"), silent("B", NodeSample.Paused))))
    d.cause shouldBe NodeUnresponsive
    d.evidence should include("B[paused]")
  }

  "Idle nodes at height 0" should "be named a stalled chain, not silence" in {
    val idle = Seq("A", "B").map(n => NodeSample(n, 0L, NodeSample.Running, answered = true, None, None, None, None, Some(1), Some(false)))
    diagnose(frozen(idle)).cause shouldBe ChainStalled
  }

  "A node frozen while the others advance" should "be named lagging even with peers listed" in {
    val history = (0 to 40).map { i =>
      Seq(node("A", 10 + i, 10 + i, s"a$i", s"a$i", at = i * 1000L), node("B", 12, 12, "b", "b", peers = 2, at = i * 1000L))
    }
    val d = diagnose(history)
    d.cause shouldBe NodeLagging
    d.evidence should include("B[running]")
  }

  it should "not be named when the group gained little" in {
    val history = (0 to 40).map { i =>
      Seq(node("A", 10 + i / 20, 10 + i / 20, "a", "a", at = i * 1000L), node("B", 10, 10, "a", "a", at = i * 1000L))
    }
    diagnose(history).cause should not be NodeLagging
  }

  "One unanswered round" should "not be named as an unresponsive node" in {
    val ok = Seq(node("A", 5, 5, "aa", "aa"), node("B", 5, 5, "aa", "aa"))
    val history = frozen(ok, 4) :+ Seq(node("A", 5, 5, "aa", "aa", at = 40000L), silent("B", at = 40000L))
    diagnose(history).cause should not be NodeUnresponsive
  }

  "A best-chain split" should "be named after two consecutive rounds, not one" in {
    val ok = Seq(node("A", 9, 9, "aa", "aa"), node("B", 9, 9, "aa", "aa"))
    val split = Seq(node("A", 9, 9, "aa", "aa"), node("B", 9, 9, "aa", "bb"))
    diagnose(frozen(ok, 4) :+ split.map(_.copy(atMillis = 40000L))).cause should not be BestChainInconsistent
    val d = diagnose(frozen(ok, 3) ++ Seq(split.map(_.copy(atMillis = 30000L)), split.map(_.copy(atMillis = 40000L))))
    d.cause shouldBe BestChainInconsistent
    d.evidence should include("B[running]")
  }

  it should "not be named while the full chain is only lagging the headers" in {
    diagnose(frozen(Seq(node("A", 9, 9, "aa", "aa"), node("B", 9, 8, "aa", "bb")))).cause should not be BestChainInconsistent
  }

  "Separate chains advancing" should "be named a persistent fork, not progress" in {
    // live run 2026-09-22 (UtxoStateNodesSyncSpec on v6.0.6): node01 873->901, node03/node04 869->898, tips never equal
    val history = (0 to 30).map { i =>
      Seq(node("A", 873 + i, 873 + i, s"a$i", s"a$i", peers = 3, mining = true, at = i * 1000L),
        node("C", 869 + i, 869 + i, s"c$i", s"c$i", peers = 3, mining = true, at = i * 1000L),
        node("D", 869 + i, 869 + i, s"d$i", s"d$i", peers = 3, mining = true, at = i * 1000L))
    }
    val d = diagnose(history)
    d.cause shouldBe PersistentFork
    d.evidence should include("not switching")
  }

  it should "be named with staggered heights too" in {
    val history = (0 to 30).map { i =>
      Seq(node("A", 901 + i, 901 + i, s"a$i", s"a$i", mining = true, at = i * 1000L),
        node("C", 899 + i, 899 + i, s"c$i", s"c$i", mining = true, at = i * 1000L),
        node("D", 897 + i, 897 + i, s"d$i", s"d$i", mining = true, at = i * 1000L))
    }
    diagnose(history).cause shouldBe PersistentFork
  }

  it should "not be named for a follower behind on the same chain" in {
    // B shows A's tip of two rounds earlier
    val history = (0 to 30).map { i =>
      Seq(node("A", 100 + i, 100 + i, s"a$i", s"a$i", at = i * 1000L), node("B", 98 + i, 98 + i, s"a${i - 2}", s"a${i - 2}", at = i * 1000L))
    }
    diagnose(history).cause should not be PersistentFork
  }

  it should "not be named while only one chain advances" in {
    val history = (0 to 30).map { i =>
      Seq(node("A", 100 + i, 100 + i, s"a$i", s"a$i", at = i * 1000L), node("B", 90, 90, "b", "b", at = i * 1000L))
    }
    diagnose(history).cause should not be PersistentFork
  }

  "A moving chain" should "be named still-progressing, with a rate" in {
    val history = (0 until 5).map(i => Seq(node("A", 10 + i, 10 + i, s"a$i", s"a$i", at = i * 1000L),
      node("B", 10 + i, 9 + i, s"a$i", s"a${i - 1}", at = i * 1000L)))
    val d = diagnose(history)
    d.cause shouldBe StillProgressing
    d.evidence should include("blocks/min")
  }

  "No samples" should "be unknown, and say so" in {
    diagnose(Seq.empty).cause shouldBe Unknown
    report(Seq.empty, "goal") should include("CAUSE UNKNOWN")
  }
}
