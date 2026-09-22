package org.ergoplatform.it.util

import scala.concurrent.duration._

/**
  * One node's state at one sampling instant. `answered` says whether REST answered at all: a node at
  * height 0 answers with empty heights, which is not the same as silence.
  */
final case class NodeSample(name: String,
                            atMillis: Long,
                            containerState: String,
                            answered: Boolean,
                            headersHeight: Option[Int],
                            fullHeight: Option[Int],
                            bestHeaderId: Option[String],
                            bestFullId: Option[String],
                            peers: Option[Int],
                            mining: Option[Boolean]) {

  /** A paused container still exists (silence, not death); an unanswered `inspect` proves nothing. */
  def running: Boolean =
    containerState == NodeSample.Running || containerState == NodeSample.Paused || containerState == NodeSample.Unknown

  /** Full block and header at the same height but on different forks. */
  def bestChainInconsistent: Boolean =
    fullHeight.nonEmpty && fullHeight == headersHeight &&
      bestFullId.nonEmpty && bestHeaderId.nonEmpty && bestFullId != bestHeaderId

  def render: String = {
    def short(id: Option[String]): String = id.map(_.take(8)).getOrElse("-")
    def num(v: Option[Int]): String = v.map(_.toString).getOrElse("-")
    s"$name[$containerState] headers=${num(headersHeight)}:${short(bestHeaderId)} " +
      s"full=${num(fullHeight)}:${short(bestFullId)} peers=${num(peers)} " +
      s"mining=${mining.map(_.toString).getOrElse("-")}"
  }
}

object NodeSample {
  val Running = "running"
  val Paused = "paused"
  val Unknown = "unknown"
}

/** A named reason for a multi-node test not reaching its goal, with the evidence for it. */
final case class Diagnosis(cause: String, evidence: String) {
  override def toString: String = s"$cause: $evidence"
}

/**
  * Names why a group of nodes did not converge, from a short history of their sampled state.
  *
  * A round is one sample of every node; `history` is oldest first. The rules are ordered: infrastructure
  * first (a node that is down explains everything after it), then the defect that needs no history
  * (#525), then the ways a chain can stop moving, then "it was still moving".
  */
object ConvergenceDiagnosis {

  val NodeDown = "NODE_DOWN"
  val NodeUnresponsive = "NODE_UNRESPONSIVE"
  val NoPeers = "NO_PEERS"
  val NodeLagging = "NODE_LAGGING"
  val BestChainInconsistent = "BEST_CHAIN_INCONSISTENT"
  val EqualHeightTie = "EQUAL_HEIGHT_TIE"
  val HeadersAheadFullStuck = "HEADERS_AHEAD_FULL_STUCK"
  val LighterForkNotSwitching = "LIGHTER_FORK_NOT_SWITCHING"
  val ChainStalled = "CHAIN_STALLED"
  val StillProgressing = "STILL_PROGRESSING"
  val PersistentFork = "PERSISTENT_FORK"
  val Unknown = "UNKNOWN"

  /** Rounds a condition must hold for before it is named (one slow answer is not a cause). */
  val PersistRounds = 3

  /** Progress is judged over this span, so early progress cannot mask a later stall. */
  val ProgressSpan: FiniteDuration = 30.seconds

  /** A node whose heights did not move over this span while another node gained `LagBlocks` is lagging. */
  val LagSpan: FiniteDuration = 30.seconds
  val LagBlocks = 3

  /** Nodes frozen over the last `LagSpan` while the group moved: out of the network, or wedged, either way not converging. */
  def lagging(history: Seq[Seq[NodeSample]]): Seq[(NodeSample, Int)] = history.lastOption.toSeq.flatMap { last =>
    val cutoff = last.map(_.atMillis).max - LagSpan.toMillis
    val window = history.filter(_.exists(_.atMillis >= cutoff))
    if (window.size < 2) Seq.empty
    else {
      def gain(name: String): Option[Int] = for {
        first <- window.head.find(_.name == name).flatMap(_.fullHeight)
        end <- last.find(_.name == name).flatMap(_.fullHeight)
      } yield end - first
      val best = last.flatMap(n => gain(n.name)).reduceOption(_ max _).getOrElse(0)
      if (best < LagBlocks) Seq.empty
      else last.flatMap { n =>
        val moved = window.head.find(_.name == n.name).exists(f => f.fullHeight != n.fullHeight || f.headersHeight != n.headersHeight)
        if (n.answered && !moved) Some(n -> best) else None
      }
    }
  }

  /**
    * @param expectPeers false for a phase that isolates nodes on purpose (`NO_PEERS` is then not a cause)
    */
  def diagnose(history: Seq[Seq[NodeSample]], expectPeers: Boolean = true): Diagnosis = {
    if (history.isEmpty || history.last.isEmpty) Diagnosis(Unknown, "no samples were taken")
    else {
      val last = history.last
      val tail = history.takeRight(PersistRounds)

      // a condition is named only when it held in every retained round, and there were at least two
      def persistent(name: String)(p: NodeSample => Boolean): Boolean =
        tail.size >= 2 && tail.forall(_.find(_.name == name).exists(p))

      val down = last.filter(n => persistent(n.name)(!_.running))
      val silent = last.filter(n => n.running && persistent(n.name)(!_.answered))
      val lonely = if (expectPeers) last.filter(n => persistent(n.name)(_.peers.contains(0))) else Seq.empty
      // two consecutive rounds, the same rule the delay spec applies to its own samples
      val inconsistent = last.filter(n => history.takeRight(2).size == 2 &&
        history.takeRight(2).forall(_.find(_.name == n.name).exists(_.bestChainInconsistent)))
      val stuckFull = fullStuckBehindHeaders(history)
      val laggers = lagging(history).filterNot { case (n, _) => stuckFull.exists(_.name == n.name) }

      if (down.nonEmpty) Diagnosis(NodeDown, down.map(_.render).mkString("; "))
      else if (silent.nonEmpty) Diagnosis(NodeUnresponsive,
        s"no REST answer for $PersistRounds rounds: " + silent.map(_.render).mkString("; "))
      else if (lonely.nonEmpty) Diagnosis(NoPeers,
        s"0 connected peers for $PersistRounds rounds: " + lonely.map(_.render).mkString("; "))
      else if (inconsistent.nonEmpty) Diagnosis(BestChainInconsistent,
        "full block and header on different forks at one height: " +
          inconsistent.map(_.render).mkString("; "))
      else if (stuckFull.nonEmpty) Diagnosis(HeadersAheadFullStuck,
        s"full height unchanged for ${LagSpan.toSeconds} s while headers are ahead of it: " +
          stuckFull.map(_.render).mkString("; ") + s". All: ${last.map(_.render).mkString("; ")}")
      else if (laggers.nonEmpty) Diagnosis(NodeLagging,
        s"heights unchanged for ${LagSpan.toSeconds} s while others gained ${laggers.head._2} blocks (a removed link keeps " +
          s"its TCP peers listed for minutes, so a peer count is no proof of connectivity): " +
          laggers.map(_._1.render).mkString("; "))
      else progress(history, last)
    }
  }

  /** Nodes whose full height did not move over `LagSpan` while their headers stayed ahead of it. */
  def fullStuckBehindHeaders(history: Seq[Seq[NodeSample]]): Seq[NodeSample] = history.lastOption.toSeq.flatMap { last =>
    val cutoff = last.map(_.atMillis).max - LagSpan.toMillis
    val window = history.filter(_.exists(_.atMillis >= cutoff))
    if (window.size < 2 || window.head.map(_.atMillis).min > cutoff + LagSpan.toMillis / 2) Seq.empty
    else last.filter { n =>
      val startFull = window.head.find(_.name == n.name).flatMap(_.fullHeight)
      val behind = (for (h <- n.headersHeight; f <- n.fullHeight) yield h - f >= 3).getOrElse(false)
      n.answered && behind && startFull.nonEmpty && startFull == n.fullHeight
    }
  }

  private def progress(history: Seq[Seq[NodeSample]], last: Seq[NodeSample]): Diagnosis = {
    // judged over the last ProgressSpan, so progress early in a long wait cannot hide a later stall
    val cutoff = last.map(_.atMillis).max - ProgressSpan.toMillis
    val window = history.filter(_.exists(_.atMillis >= cutoff))
    val first = if (window.size >= 2) window.head else history.head
    val spanSeconds = (last.map(_.atMillis).max - first.map(_.atMillis).min) / 1000.0

    def heights(round: Seq[NodeSample]): Map[String, (Option[Int], Option[Int])] =
      round.filter(_.answered).map(n => n.name -> ((n.headersHeight, n.fullHeight))).toMap

    val before = heights(first); val now = heights(last)
    val common = before.keySet intersect now.keySet
    val moved = history.size >= 2 && common.exists(n => before(n) != now(n))
    val all = last.map(_.render).mkString("; ")

    // separate chains advancing: a node on the same chain as another shows that node's earlier tip ids, so
    // two nodes whose tip-id histories over the window never meet are on different chains; name it when at
    // least two such nodes kept gaining blocks (a follower behind on one chain shares ids and is not this)
    val gaining = common.toSeq.filter(n => (for (a <- before(n)._2; b <- now(n)._2) yield b - a >= LagBlocks).getOrElse(false))
    val tipHistory: Map[String, Set[String]] =
      gaining.map(n => n -> window.flatMap(_.find(_.name == n).flatMap(_.bestFullId)).toSet).toMap
    val onSeparateChains = gaining.filter(n => window.size >= 5 &&
      gaining.exists(m => m != n && (tipHistory(n) intersect tipHistory(m)).isEmpty))

    if (moved && onSeparateChains.size >= 2) {
      val advancing = onSeparateChains.size
      Diagnosis(PersistentFork,
        f"$advancing nodes kept gaining blocks over the last $spanSeconds%.0f s on chains that never shared a tip: separate chains " +
          s"are advancing and the lighter ones are not switching. $all")
    } else if (moved) {
      // per node, over the nodes present in both rounds (a node that fell silent does not count as a loss)
      val gains = common.toSeq.flatMap(n => for (a <- before(n)._2; b <- now(n)._2) yield b - a)
      val perMinute = if (spanSeconds > 0 && gains.nonEmpty) gains.sum.toDouble / gains.size / spanSeconds * 60 else 0.0
      Diagnosis(StillProgressing,
        f"heights were still moving ($perMinute%.1f blocks/min per node over the last $spanSeconds%.0f s): " +
          s"raise the timeout, or the machine is slow. $all")
    } else if (history.size < 2) {
      Diagnosis(Unknown, s"one round only, progress cannot be judged. $all")
    } else {
      val tips = last.flatMap(_.bestFullId).distinct
      val fullHeights = last.flatMap(_.fullHeight).distinct
      val anyoneMining = last.exists(_.mining.contains(true))
      // a one-block pipeline gap is normal; the name is for full blocks left well behind the headers
      val stuckBehindHeaders = last.filter(n => (for (h <- n.headersHeight; f <- n.fullHeight) yield h - f >= 3).getOrElse(false))
      val top = last.flatMap(_.fullHeight).reduceOption(_ max _)
      val lowerAndUnaware = last.filter { n =>
        n.fullHeight.nonEmpty && n.fullHeight == n.headersHeight && top.exists(t => n.fullHeight.exists(_ < t))
      }
      val frozen = f"no height moved for $spanSeconds%.0f s"

      if (stuckBehindHeaders.nonEmpty) Diagnosis(HeadersAheadFullStuck,
        s"$frozen; headers ahead of full blocks on: ${stuckBehindHeaders.map(_.render).mkString("; ")}. All: $all")
      else if (tips.size > 1 && fullHeights.size == 1) Diagnosis(EqualHeightTie,
        s"$frozen; equal heights, different tips, mining=${if (anyoneMining) "on" else "off"}. " +
          (if (anyoneMining) "A miner is on and no block came. "
           else "No later block will break the tie while nobody mines. ") + "This does not say which node is wrong. " + all)
      else if (tips.size > 1 && lowerAndUnaware.nonEmpty) Diagnosis(LighterForkNotSwitching,
        s"$frozen; a lower node never took the higher chain's headers: " +
          s"${lowerAndUnaware.map(_.render).mkString("; ")}. All: $all")
      else if (tips.size <= 1) Diagnosis(ChainStalled,
        s"$frozen and tips agree: nobody is producing blocks (mining=${if (anyoneMining) "on" else "off"}). $all")
      else Diagnosis(Unknown, s"$frozen. $all")
    }
  }

  /** The report a failing test prints: the named cause, then the last rounds in full. */
  def report(history: Seq[Seq[NodeSample]], goal: String, expectPeers: Boolean = true): String = {
    def line(label: String, round: Seq[NodeSample]): String = s"  $label: " + round.map(_.render).mkString("; ")
    val lines = history.lastOption.toSeq.flatMap { last =>
      val cutoff = last.map(_.atMillis).max - LagSpan.toMillis
      val windowStart = history.find(_.exists(_.atMillis >= cutoff)).filter(_ ne last)
      windowStart.map(r => line(f"${LagSpan.toSeconds}%d s ago", r)).toSeq ++
        history.takeRight(PersistRounds).dropRight(1).map(r => line("then", r)) :+ line("last", last)
    }
    s"$goal. CAUSE ${diagnose(history, expectPeers)}\n${lines.mkString("\n")}"
  }
}
