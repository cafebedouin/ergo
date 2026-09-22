package org.ergoplatform.it.util

import io.circe.Json
import org.ergoplatform.it.api.NodeApi.NodeInfo
import org.ergoplatform.it.container.{Docker, Node}

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Samples a group of nodes (REST state, peer count, container state), keeps the last rounds, and turns a
  * timeout into a named cause (see [[ConvergenceDiagnosis]]). A node that does not answer within the
  * budget yields an empty sample instead of failing the round.
  */
final class ConvergenceWatch(docker: Docker, nodes: Seq[Node], keep: FiniteDuration = 90.seconds,
                             expectPeers: Boolean = true)
                            (implicit ec: ExecutionContext) extends AutoCloseable {

  private val observations = new ConvergenceObservations
  private var rounds = Vector.empty[Seq[NodeSample]]
  private var sampler: Option[ScheduledExecutorService] = None

  private val probes = nodes.map { node =>
    val info = observations.probe(node.singleGet("/info", _.setRequestTimeout(5000)).map { r =>
      require(r.getStatusCode == 200, s"/info answered ${r.getStatusCode}")
      node.ergoJsonAnswerAs[NodeInfo](r.getResponseBody)
    })
    val peers = observations.probe(node.singleGet("/peers/connected", _.setRequestTimeout(5000)).map { r =>
      require(r.getStatusCode == 200, s"/peers/connected answered ${r.getStatusCode}")
      node.ergoJsonAnswerAs[Json](r.getResponseBody).asArray.map(_.size).getOrElse(0)
    })
    (node, info, peers)
  }

  def history: Seq[Seq[NodeSample]] = synchronized(rounds)

  /** Adds a round a spec assembled from answers it already has, so no second request is made. */
  def record(round: Seq[NodeSample]): Unit = synchronized {
    val cutoff = System.currentTimeMillis() - keep.toMillis
    rounds = (rounds :+ round).dropWhile(_.forall(_.atMillis < cutoff))
  }

  /** One round. With `withContainerState = false` the docker daemon is not asked (for fast polling). */
  def sample(budget: FiniteDuration = 3.seconds, withContainerState: Boolean = true): Future[Seq[NodeSample]] =
    Future.traverse(probes) { case (node, infoProbe, peersProbe) =>
      infoProbe.sample(budget).zip(peersProbe.sample(budget)).map { case (info, peers) =>
        val state =
          if (withContainerState || info.isLeft) docker.containerState(node.nodeInfo.containerId)
          else NodeSample.Running
        val i = info.toOption
        NodeSample(node.nodeName, System.currentTimeMillis(), state, info.isRight,
          i.flatMap(_.bestHeaderHeightOpt), i.flatMap(_.bestBlockHeightOpt),
          i.flatMap(_.bestHeaderIdOpt), i.flatMap(_.bestBlockIdOpt),
          peers.toOption, i.flatMap(_.isMining))
      }
    }.map { round =>
      synchronized {
        // kept by time, so the lag and progress spans (30 s) are always covered whatever the sampling rate
        val cutoff = System.currentTimeMillis() - keep.toMillis
        rounds = (rounds :+ round).dropWhile(_.forall(_.atMillis < cutoff))
      }
      round
    }

  /** The named cause and last rounds; for nodes that are down or silent, also the end of their own log. */
  /**
    * Samples in the background every `interval` until `close()`, for waits that are not driven through
    * `until` (a `waitForHeight`, an outer `Await`): the report is then available from any catch block.
    */
  def startSampling(interval: FiniteDuration = 1.second): this.type = synchronized {
    if (sampler.isEmpty) {
      val executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
        override def newThread(runnable: Runnable): Thread = {
          val thread = new Thread(runnable, "convergence-watch-sampler")
          thread.setDaemon(true)
          thread
        }
      })
      executor.scheduleWithFixedDelay(new Runnable {
        // the round is awaited, so rounds never overlap however slow the REST answers are
        override def run(): Unit =
          try Await.result(sample(3.seconds, withContainerState = false), 5.seconds) catch { case _: Throwable => () }
      }, 0L, interval.toMillis, TimeUnit.MILLISECONDS)
      sampler = Some(executor)
    }
    this
  }

  def report(goal: String): String = {
    val troubled = history.lastOption.toSeq.flatten.filter(n => !n.answered || n.containerState != NodeSample.Running)
    val tails = troubled.flatMap { sample =>
      nodes.find(_.nodeName == sample.name).map { node =>
        s"  last log lines of ${sample.name}:\n" +
          docker.logTail(node.nodeInfo.containerId).linesIterator.map("    " + _).mkString("\n")
      }
    }
    (ConvergenceDiagnosis.report(history, goal, expectPeers) +: tails).mkString("\n")
  }

  /** Samples until `accept` holds; at the deadline fails with the named cause and the last rounds. */
  def until(deadline: Deadline, interval: FiniteDuration = 1.second)
           (accept: Seq[NodeSample] => Boolean)(goal: String): Future[Seq[NodeSample]] =
    observations.until(deadline, interval, 5.seconds)(budget => sample(budget))(accept)(report(goal))

  /** Like `until`, but the observation is `observe` (run after each sample), so the goal can be anything. */
  def untilWith[A](deadline: Deadline, interval: FiniteDuration)
                  (observe: FiniteDuration => Future[A])(accept: A => Boolean)(goal: => String): Future[A] =
    observations.until(deadline, interval, 5.seconds)(budget => sample(budget).flatMap(_ => observe(budget)))(accept)(report(goal))

  override def close(): Unit = {
    synchronized {
      sampler.foreach { s => s.shutdownNow(); s.awaitTermination(2, TimeUnit.SECONDS) }
      sampler = None
    }
    observations.close()
  }
}
