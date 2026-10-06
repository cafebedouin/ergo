package org.ergoplatform.mining

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.pattern.StatusReply
import com.google.common.primitives.Longs
import org.ergoplatform.{AutolykosSolution, InputBlockFound, InputSolutionFound, NothingFound, OrderingBlockFound, OrderingSolutionFound}
import org.ergoplatform.mining.CandidateGenerator.{Candidate, GenerateCandidate, SubscribeCandidates}
import org.ergoplatform.network.ErgoNodeViewSynchronizerMessages.FullBlockApplied
import org.ergoplatform.settings.{ErgoSettings, Parameters}
import scorex.util.ScorexLogging

import scala.concurrent.duration._
import scala.util.Random

/** ErgoMiningThread is a scala implementation of a miner using just CPU.
  * It tries to mimic GPU miner's behavior as to polling for new Candidates
  * and submitting solutions. Note that it is useful only for low mining difficulty
  * as its hashrate is just 1000 h/s */
class ErgoMiningThread(
  ergoSettings: ErgoSettings,
  candidateGenerator: ActorRef,
  sk: PrivateKey
) extends Actor
  with ScorexLogging {

  import org.ergoplatform.mining.ErgoMiningThread._

  private val powScheme = ergoSettings.chainSettings.powScheme
  // the thread reads its mailbox (a new candidate) only between steps; a short step keeps that latency low
  private val NonceStep = 100

  override def preStart(): Unit = {
    log.info(s"Starting miner thread: ${self.path.name}")
    // new candidates are pushed as soon as they are built; the periodic poll below stays as a fallback
    candidateGenerator ! SubscribeCandidates
    // poll for new candidate periodically
    context.system.scheduler.scheduleWithFixedDelay(
      1.second,
      ergoSettings.nodeSettings.internalMinerPollingInterval,
      candidateGenerator,
      GenerateCandidate(Seq.empty, reply = true, forced = false, optPk = None)
    )(context.dispatcher, self)
    // and at once when a block is applied, rather than mining its parent until the next poll
    context.system.eventStream.subscribe(self, classOf[FullBlockApplied])
  }

  private def requestCandidate(): Unit =
    candidateGenerator ! GenerateCandidate(Seq.empty, reply = true, forced = false, optPk = None)

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.error(s"Attempted mining thread restart due to ${reason.getMessage}", reason)
    super.preRestart(reason, message)
  }

  override def postStop(): Unit =
    log.info(s"Stopping miner thread: ${self.path.name}")

  override def receive: Receive = {
    case _: FullBlockApplied => requestCandidate()
    case StatusReply.Success(Candidate(candidateBlock, _, _, parameters, _)) =>
      log.info(s"Initiating block mining")
      startChain(epoch = 1, nonce = 0, candidateBlock, parameters, solvedBlocksCount = 0)
    case StatusReply.Error(ex) =>
      log.error(s"Preparing candidate did not succeed", ex)
  }

  /** Becomes the given state and starts its MineCmd chain; MineCmds of older epochs are dropped (one chain at a time). */
  private def startChain(epoch: Long, nonce: Int, candidateBlock: CandidateBlock, parameters: Parameters,
                         solvedBlocksCount: Int): Unit = {
    context.become(mining(epoch, nonce, candidateBlock, parameters, solvedBlocksCount))
    self ! MineCmd(epoch)
  }

  /**
    * @param epoch id of the one MineCmd chain allowed to run; a new candidate or a restart after an error bumps it
    */
  def mining(
    epoch: Long,
    nonce: Int,
    candidateBlock: CandidateBlock,
    parameters: Parameters,
    solvedBlocksCount: Int
  ): Receive = {
    case StatusReply.Success(Candidate(cb, _, _, newParameters, _)) =>
      // if we get new candidate instead of a cached one, mine it
      if (cb.timestamp != candidateBlock.timestamp) {
        startChain(epoch + 1, nonce = 0, cb, newParameters, solvedBlocksCount)
      }
    case StatusReply.Error(ex) =>
      log.error(s"Accepting solution or preparing candidate did not succeed", ex)
      // resume after the rejected solution's nonce (recorded when it was found), not at the batch start
      startChain(epoch + 1, nonce, candidateBlock, parameters, solvedBlocksCount)
    case StatusReply.Success(()) =>
      log.info(s"Solution accepted")
      context.become(mining(epoch, nonce, candidateBlock, parameters, solvedBlocksCount + 1))
    case MineCmd(e) if e != epoch =>
      // a chain of an earlier candidate or restart: drop it
    case MineCmd(_) =>
      val lastNonceToCheck = nonce + NonceStep
      powScheme.proveCandidate(candidateBlock, sk, nonce, lastNonceToCheck, parameters) match {
        case OrderingBlockFound(newBlock) =>
          log.info(s"Found solution for ordering block, sending it for validation")
          context.become(mining(epoch, nonceAfter(newBlock.header.powSolution), candidateBlock, parameters, solvedBlocksCount))
          candidateGenerator ! OrderingSolutionFound(newBlock.header.powSolution)
        case InputBlockFound(newBlock) =>
          log.info(s"Found solution for input block, sending it for validation")
          context.become(mining(epoch, nonceAfter(newBlock.header.powSolution), candidateBlock, parameters, solvedBlocksCount))
          candidateGenerator ! InputSolutionFound(newBlock.header.powSolution)
        case NothingFound =>
          log.info(s"Trying nonce $lastNonceToCheck")
          context.become(mining(epoch, lastNonceToCheck, candidateBlock, parameters, solvedBlocksCount))
          self ! MineCmd(epoch)
        case _ =>
          //todo : rework ProveBlockResult hierarchy to avoid this branch
      }
    case _: FullBlockApplied => requestCandidate()
    case GetSolvedBlocksCount =>
      sender() ! SolvedBlocksCount(solvedBlocksCount)
  }

  private def nonceAfter(solution: AutolykosSolution): Int = Longs.fromByteArray(solution.n).toInt + 1

}

object ErgoMiningThread {

  /** One step of the nonce search; only the chain of the current `epoch` runs. */
  case class MineCmd(epoch: Long)
  case object GetSolvedBlocksCount // metric just for testing purposes for now
  case class SolvedBlocksCount(count: Int)

  private def props(ergoSettings: ErgoSettings, minerRef: ActorRef, sk: BigInt): Props =
    Props(new ErgoMiningThread(ergoSettings, minerRef, sk))

  def apply(ergoSettings: ErgoSettings, minerRef: ActorRef, sk: BigInt)(
    implicit context: ActorRefFactory
  ): ActorRef =
    context.actorOf(props(ergoSettings, minerRef, sk), s"ErgoMiningThread-${Random.alphanumeric.take(5).mkString}")

}
