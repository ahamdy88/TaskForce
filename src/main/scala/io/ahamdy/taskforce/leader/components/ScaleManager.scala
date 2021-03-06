package io.ahamdy.taskforce.leader.components

import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference

import cats.syntax.flatMap._
import monix.eval.Task
import io.ahamdy.taskforce.api.{CloudManager, NodeInfoProvider}
import io.ahamdy.taskforce.common.Time
import io.ahamdy.taskforce.domain.{NodeActive, NodeId}
import io.ahamdy.taskforce.store.NodeStore
import io.ahamdy.taskforce.syntax.IOType._
import io.ahamdy.taskforce.syntax.zonedDateTime._

import scala.concurrent.duration.FiniteDuration

trait ScaleManager {
  def scaleCluster(queuedAndRunningWeights: Int, activeNodesCapacity: Int): Task[Unit]
  def cleanInactiveNodes(currentNodesRunningJobs: Set[NodeId]): Task[Unit]
}

class ScaleManagerImpl(config: ScaleManagerConfig, cloudManager: CloudManager, nodeInfoProvider: NodeInfoProvider,
                       nodeStore: NodeStore, time: Time) extends ScaleManager {
  val lastScaleActivity: AtomicReference[ZonedDateTime] = new AtomicReference(time.epoch)

  val scaleUpNeededSince: AtomicReference[Option[ZonedDateTime]] = new AtomicReference(None)
  val scaleDownNeededSince: AtomicReference[Option[ZonedDateTime]] = new AtomicReference(None)

  /**
    * Effects:
    * 1- set scaleDownNeededSince to None
    * 2- set scaleUpNeededSince to None
    *
    * @param queuedAndRunningWeights
    * @param activeNodesCapacity
    * @return
    */

  override def scaleCluster(queuedAndRunningWeights: Int, activeNodesCapacity: Int): Task[Unit] = {
    time.now.flatMap { now =>
      if (now.minus(lastScaleActivity.get()) >= config.coolDownPeriod)
        if ((queuedAndRunningWeights / activeNodesCapacity.toDouble) * 100 > config.scaleUpThreshold)
          Task(scaleDownNeededSince.set(None)) >> scaleUpIfDue(now)
        else if ((queuedAndRunningWeights / activeNodesCapacity.toDouble) * 100 < config.scaleDownThreshold)
          Task(scaleUpNeededSince.set(None)) >> scaleDownIfDue(now)
        else
          Task(scaleUpNeededSince.set(None)) >> Task(scaleDownNeededSince.set(None))
      else
        Task.unit
    }
  }

  override def cleanInactiveNodes(currentNodesRunningJobs: Set[NodeId]): Task[Unit] =
    for {
      inactiveNodes <- nodeStore.getAllInactiveNodesByGroup(nodeInfoProvider.nodeGroup)
      idleInactiveNodes <- Task.pure(inactiveNodes.map(_.nodeId).filterNot(currentNodesRunningJobs.contains).toSet)
      _ <- cloudManager.scaleDown(idleInactiveNodes)
    } yield ()

  /**
    * Effects:
    * 1- set scaleUpNeededSince to None
    * 2- set scaleUpNeededSince to Some(now)
    * 3- set lastScaleActivity to now
    * 4- scale up cluster
    *
    * @param now current ZonedDateTime
    * @return
    */

  def scaleUpIfDue(now: ZonedDateTime): Task[Unit] =
    scaleUpNeededSince.get() match {
      case None => Task(scaleUpNeededSince.set(Some(now)))
      case Some(scaleUpNeededTime) if now.minus(scaleUpNeededTime) >= config.evaluationPeriod =>
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup).flatMap {
          case nodesCount if nodesCount < config.maxNodes =>
            cloudManager.scaleUp(Math.min(config.scaleUpStep, config.maxNodes - nodesCount)) >>
              Task(lastScaleActivity.set(now)) >> Task(scaleUpNeededSince.set(None))
          case _ =>
            Task.unit
        }
      case Some(_) => Task.unit
    }

  def scaleDownIfDue(now: ZonedDateTime): Task[Unit] =
    scaleDownNeededSince.get match {
      case None => Task(scaleDownNeededSince.set(Some(now)))
      case Some(scaleDownNeededTime) if now.minus(scaleDownNeededTime) >= config.evaluationPeriod =>
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup).flatMap {
          case nodesCount if nodesCount > config.minNodes =>
            (for {
              nodes <- nodeStore.getYoungestActiveNodesByGroup(nodeInfoProvider.nodeGroup, Math.min(config.scaleDownStep, nodesCount - config.minNodes))
              _ <- sequenceUnit(nodes.map(node => nodeStore.updateNodeStatus(node.nodeId, NodeActive(false))))
            } yield ()) >>
              Task(lastScaleActivity.set(now)) >>
              Task(scaleDownNeededSince.set(None))
          case _ => Task.unit
        }
      case Some(_) => Task.unit
    }
}

case class ScaleManagerConfig(minNodes: Int, maxNodes: Int, coolDownPeriod: FiniteDuration, scaleDownThreshold: Int,
                              scaleUpThreshold: Int, evaluationPeriod: FiniteDuration, scaleUpStep: Int, scaleDownStep: Int)
