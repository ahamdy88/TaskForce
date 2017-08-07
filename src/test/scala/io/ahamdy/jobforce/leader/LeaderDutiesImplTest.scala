package io.ahamdy.jobforce.leader

import java.time.{ZoneId, ZonedDateTime}
import java.util.concurrent.atomic.AtomicReference

import com.cronutils.model.CronType
import fs2.Task
import io.ahamdy.jobforce.common.{DummyTime, Time}
import io.ahamdy.jobforce.domain._
import io.ahamdy.jobforce.scheduling.{CronLine, DummyJobsScheduleProvider, JobsScheduleProvider}
import io.ahamdy.jobforce.shared.{DummyNodeInfoProvider, NodeInfoProvider}
import io.ahamdy.jobforce.store.{DummyJobStore, DummyNodeStore, JobsStore, NodeStore}
import io.ahamdy.jobforce.testing.StandardSpec
import io.ahamdy.jobforce.testing.syntax.either._

import scala.collection.JavaConverters._
import scala.concurrent.duration._


class LeaderDutiesImplTest extends StandardSpec {

  sequential

  val scheduledJob = ScheduledJob(
    JobId("job-id-1"),
    JobLock("test-lock-1"),
    JobType("test-type-1"),
    JobWeight(5),
    Map.empty,
    JobSchedule(CronLine.parse("0 * * * * ?", CronType.QUARTZ, ZoneId.of("UTC")).getRight, 2.minute),
    JobMaxAttempts(5),
    JobPriority(2)
  )

  val dummyTime = new DummyTime(ZonedDateTime.now())

  val node1InfoProvider = new DummyNodeInfoProvider("test-node-1", "test-group-1")
  val node2InfoProvider = new DummyNodeInfoProvider("test-node-2", "test-group-1")
  val node3InfoProvider = new DummyNodeInfoProvider("test-node-3", "test-group-2")

  val jobsScheduleProvider = new DummyJobsScheduleProvider

  val nodeStore = new DummyNodeStore(dummyTime)

  val jobStore = new DummyJobStore(dummyTime)

  def createNewLeader(config: JobForceLeaderConfig = config,
                      nodeInfoProvider: NodeInfoProvider = node1InfoProvider,
                      jobsScheduleProvider: JobsScheduleProvider = jobsScheduleProvider,
                      nodeStore: NodeStore = nodeStore,
                      jobsStore: JobsStore = jobStore,
                      time: Time = dummyTime) =
    new LeaderDutiesImpl(
      config,
      nodeInfoProvider,
      jobsScheduleProvider,
      nodeStore,
      jobsStore,
      time
    )

  val config = JobForceLeaderConfig(
    minActiveNodes = 2,
    maxWeightPerNode = 100,
    youngestLeaderAge = 10.second,
    leaderAlsoWorker = true)

  "LeaderDutiesImpl.electClusterLeader" should {
    "elect oldest node as leader" in {
      jobStore.reset()
      val leader = createNewLeader()
      val nonLeader = createNewLeader(nodeInfoProvider = node2InfoProvider)


      leader.isLeader must beFalse
      nonLeader.isLeader must beFalse

      leader.electClusterLeader must beSucceedingTask
      nonLeader.electClusterLeader must beSucceedingTask

      leader.isLeader must beTrue
      nonLeader.isLeader must beFalse
    }

    "refresh ScheduledJobs, QueuedJobs and runningJobs in leader cache when leader is elected" in {
      jobStore.reset()

      jobsScheduleProvider.scheduledJobs.append(scheduledJob)

      val queuedJob = scheduledJob.copy(id = JobId("job-id-2")).toQueuedJob(dummyTime.unsafeNow())
      jobStore.queuedJobStore.put(queuedJob.id, queuedJob)

      val runningJob = queuedJob.copy(id = JobId("job-id-3"))
        .toRunningJobAndIncAttempts(NodeId("test-node-2"), dummyTime.unsafeNow())
      jobStore.runningJobStore.put(runningJob.lock, runningJob)

      val leader = createNewLeader()
      leader.electClusterLeader must beSucceedingTask

      leader.scheduledJobs.get().toSet mustEqual Set(scheduledJob)
      leader.queuedJobs.values().asScala.toSet mustEqual Set(queuedJob)
      leader.runningJobs.values().asScala.toSet mustEqual Set(runningJob)
    }

    "never elect a leader if the oldest node in the group is younger than configured youngestLeaderAge" in {
      jobStore.reset()
      val modifiedNodeStore = new NodeStore {
        override def getAllNodes: Task[List[JobNode]] = Task.now(List(
          JobNode(NodeId("test-node-1"), NodeGroup("test-group-1"), dummyTime.unsafeNow().minusSeconds(2), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-2"), NodeGroup("test-group-1"), dummyTime.unsafeNow().minusSeconds(1), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-3"), NodeGroup("test-group-2"), dummyTime.unsafeNow().minusMinutes(1), NodeActive(true), NodeVersion("1.0.0"))
        ))
      }

      val leader = createNewLeader(nodeStore = modifiedNodeStore)
      val nonLeader = createNewLeader(nodeStore = modifiedNodeStore, nodeInfoProvider = node2InfoProvider)

      leader.isLeader must beFalse
      nonLeader.isLeader must beFalse

      leader.electClusterLeader must beSucceedingTask
      nonLeader.electClusterLeader must beSucceedingTask

      leader.isLeader must beFalse
      nonLeader.isLeader must beFalse
    }
  }

  "LeaderDutiesImpl.refreshQueuedJobs" should {
    "refresh QueuedJobs in leader cache and do nothing if not a leader" in {
      jobStore.reset()

      val leader = createNewLeader()
      val nonLeader = createNewLeader(nodeInfoProvider = node2InfoProvider)

      leader.electClusterLeader must beSucceedingTask

      leader.queuedJobs.values().asScala must beEmpty
      nonLeader.queuedJobs.values().asScala must beEmpty

      val queuedJob = scheduledJob.toQueuedJob(dummyTime.unsafeNow())
      jobStore.queuedJobStore.put(queuedJob.id, queuedJob)

      leader.refreshQueuedJobs must beSucceedingTask
      nonLeader.refreshQueuedJobs must beSucceedingTask

      leader.queuedJobs.values().asScala.toSet mustEqual Set(queuedJob)
      nonLeader.queuedJobs.values().asScala must beEmpty
    }
  }

  "LeaderDutiesImpl.refreshJobsSchedule" should {
    "refresh ScheduledJobs in leader cache only if leader or leaderIgnore flag is true" in {
      jobStore.reset()
      jobsScheduleProvider.reset()

      val leader = createNewLeader()
      val nonLeader = createNewLeader(nodeInfoProvider = node2InfoProvider)

      leader.electClusterLeader must beSucceedingTask

      jobsScheduleProvider.scheduledJobs.append(scheduledJob)

      leader.refreshJobsSchedule() must beSucceedingTask
      nonLeader.refreshJobsSchedule() must beSucceedingTask

      leader.scheduledJobs.get.toSet mustEqual Set(scheduledJob)
      nonLeader.scheduledJobs.get.toSet must beEmpty

      val scheduledJob2 = scheduledJob.copy(id = JobId("job-id-2"))
      jobsScheduleProvider.scheduledJobs.append(scheduledJob2)

      leader.refreshJobsSchedule() must beSucceedingTask
      nonLeader.refreshJobsSchedule() must beSucceedingTask

      leader.scheduledJobs.get.toSet mustEqual Set(scheduledJob, scheduledJob2)
      nonLeader.scheduledJobs.get.toSet must beEmpty

      nonLeader.refreshJobsSchedule(ignoreLeader = true) must beSucceedingTask
      leader.scheduledJobs.get.toSet mustEqual Set(scheduledJob, scheduledJob2)
    }
  }

  "LeaderDutiesImpl.queueScheduledJobs" should {
    "queue due scheduled jobs only if leader and if the job is not already queued or running" in {
      jobStore.reset()
      jobsScheduleProvider.reset()

      val leader = createNewLeader()

      leader.electClusterLeader must beSucceedingTask

      jobStore.isEmpty must beTrue
      jobsScheduleProvider.scheduledJobs must beEmpty
      leader.queuedJobs.isEmpty must beTrue

      leader.queueScheduledJobs must beSucceedingTask

      leader.queuedJobs.isEmpty must beTrue

      jobsScheduleProvider.scheduledJobs.append(scheduledJob)
      leader.refreshJobsSchedule() must beSucceedingTask

      leader.queueScheduledJobs must beSucceedingTask

      leader.queuedJobs.values().asScala.toList mustEqual List(scheduledJob.toQueuedJob(dummyTime.unsafeNow()))
    }
  }

  "LeaderDutiesImpl.assignQueuedJobs" should {
    "assign queued jobs to active nodes with respect to load balancing and version requirements" in {

      val scheduledJob1 = scheduledJob.copy(id = JobId("test-job1"), lock = JobLock("lock-1"),
        weight = JobWeight(100), priority = JobPriority(2))
      val scheduledJob2 = scheduledJob.copy(id = JobId("test-job2"), lock = JobLock("lock-2"),
        weight = JobWeight(100), priority = JobPriority(3))

      val queuedJob1 = scheduledJob1.toQueuedJob(dummyTime.unsafeNow())
      val queuedJob2 = scheduledJob2.toQueuedJob(dummyTime.unsafeNow())

      val runningJob1 = queuedJob1.toRunningJobAndIncAttempts(NodeId("test-node-1"), dummyTime.unsafeNow())
      val runningJob2 = queuedJob2.toRunningJobAndIncAttempts(NodeId("test-node-2"), dummyTime.unsafeNow())

      jobStore.reset()
      jobsScheduleProvider.reset()
      jobsScheduleProvider.scheduledJobs.append(scheduledJob1, scheduledJob2)

      val leader = createNewLeader()

      leader.electClusterLeader must beSucceedingTask
      leader.isLeader must beTrue
      leader.queueScheduledJobs must beSucceedingTask


      leader.queuedJobs.values().asScala must containTheSameElementsAs(List(queuedJob1, queuedJob2))
      jobStore.queuedJobStore.values().asScala must containTheSameElementsAs(List(queuedJob1, queuedJob2))

      leader.assignQueuedJobs must beSucceedingTask

      leader.queuedJobs.values().asScala must beEmpty
      jobStore.queuedJobStore.values().asScala must beEmpty

      leader.runningJobs.values().asScala must containTheSameElementsAs(List(runningJob1, runningJob2))
      jobStore.runningJobStore.values().asScala must containTheSameElementsAs(List(runningJob1, runningJob2))
    }
  }

  "not assign jobs to any node if the least loaded node will be loaded with more than maxWeightPerNode ordered by priority and jobId" in {
    jobStore.reset()
    jobsScheduleProvider.reset()

    val scheduledJob1 =
      scheduledJob.copy(id = JobId("test-job1"), lock = JobLock("lock-1"), weight = JobWeight(100), priority = JobPriority(2))
    val scheduledJob2 =
      scheduledJob.copy(id = JobId("test-job2"), lock = JobLock("lock-2"), weight = JobWeight(100), priority = JobPriority(3))
    val scheduledJob3 =
      scheduledJob.copy(id = JobId("test-job3"), lock = JobLock("lock-3"), weight = JobWeight(100), priority = JobPriority(2))
    val scheduledJob4 =
      scheduledJob.copy(id = JobId("test-job4"), lock = JobLock("lock-4"), weight = JobWeight(100), priority = JobPriority(1))


    val queuedJob2 = scheduledJob2.toQueuedJob(dummyTime.unsafeNow())
    val queuedJob3 = scheduledJob3.toQueuedJob(dummyTime.unsafeNow())

    val runningJob1 = scheduledJob1.toQueuedJob(dummyTime.unsafeNow())
      .toRunningJobAndIncAttempts(NodeId("test-node-2"), dummyTime.unsafeNow()) // second highest priority
    val runningJob4 = scheduledJob4.toQueuedJob(dummyTime.unsafeNow())
      .toRunningJobAndIncAttempts(NodeId("test-node-1"), dummyTime.unsafeNow()) // highest priority

    jobsScheduleProvider.scheduledJobs.append(
      scheduledJob1,
      scheduledJob2,
      scheduledJob3,
      scheduledJob4,
    )

    val leader = createNewLeader()
    leader.electClusterLeader must beSucceedingTask
    leader.isLeader must beTrue

    leader.queueScheduledJobs must beSucceedingTask
    leader.assignQueuedJobs must beSucceedingTask


    jobStore.queuedJobStore.values().asScala must containTheSameElementsAs(List(queuedJob2, queuedJob3))
    jobStore.runningJobStore.values().asScala must containTheSameElementsAs(List(runningJob1, runningJob4))
  }

  "LeaderDutiesImpl.cleanDeadNodesJobs" should {
    "move running jobs back to queue only if they were running on dead node and attempts limit not reached," +
      "move running jobs to finished jobs if they were running on dead node and attempts limit has been reached" in {
      jobStore.reset()
      jobsScheduleProvider.reset()

      val mutableNodeStore = new NodeStore {
        val nodes = new AtomicReference[List[JobNode]](List(
          JobNode(NodeId("test-node-1"), NodeGroup("test-group-1"), dummyTime.unsafeNow().minusMinutes(2), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-2"), NodeGroup("test-group-1"), dummyTime.unsafeNow().minusMinutes(1), NodeActive(true), NodeVersion("1.0.0"))
        ))

        override def getAllNodes: Task[List[JobNode]] = Task.now(nodes.get())
      }

      val scheduledJob1 = scheduledJob.copy(id = JobId("test-job1"), lock = JobLock("lock-1"),
        weight = JobWeight(100), priority = JobPriority(1))
      val scheduledJob2 = scheduledJob.copy(id = JobId("test-job2"), lock = JobLock("lock-2"),
        weight = JobWeight(50), priority = JobPriority(1))
      val scheduledJob3 = scheduledJob.copy(id = JobId("test-job3"), lock = JobLock("lock-3"),
        weight = JobWeight(50), priority = JobPriority(1), maxAttempts = JobMaxAttempts(1))

      val queuedJob1 = scheduledJob1.toQueuedJob(dummyTime.unsafeNow())
      val queuedJob2 = scheduledJob2.toQueuedJob(dummyTime.unsafeNow())
      val queuedJob3 = scheduledJob3.toQueuedJob(dummyTime.unsafeNow())

      val runningJob1 = queuedJob1.toRunningJobAndIncAttempts(NodeId("test-node-1"), dummyTime.unsafeNow())
      val runningJob2 = queuedJob2.toRunningJobAndIncAttempts(NodeId("test-node-2"), dummyTime.unsafeNow())
      val runningJob3 = queuedJob3.toRunningJobAndIncAttempts(NodeId("test-node-2"), dummyTime.unsafeNow())

      jobStore.runningJobStore.put(runningJob1.lock, runningJob1)
      jobStore.runningJobStore.put(runningJob2.lock, runningJob2)
      jobStore.runningJobStore.put(runningJob3.lock, runningJob3)

      val leader = createNewLeader(nodeStore = mutableNodeStore)
      leader.electClusterLeader must beSucceedingTask
      leader.isLeader must beTrue

      leader.cleanDeadNodesJobs() must beSucceedingTask

      leader.queuedJobs.isEmpty must beTrue
      jobStore.queuedJobStore.isEmpty must beTrue

      leader.runningJobs.values().asScala must containTheSameElementsAs(List(runningJob1, runningJob2, runningJob3))
      jobStore.runningJobStore.values().asScala must containTheSameElementsAs(List(runningJob1, runningJob2, runningJob3))

      mutableNodeStore.nodes.set(List(
        JobNode(NodeId("test-node-1"), NodeGroup("test-group-1"), dummyTime.unsafeNow().minusSeconds(2), NodeActive(true), NodeVersion("1.0.0"))))

      leader.cleanDeadNodesJobs() must beSucceedingTask

      val queuedJob2WithAttempt = queuedJob2.copy(attempts = queuedJob2.attempts.incAttempts)
      val finishedJob3 = runningJob3.toFinishedJob(dummyTime.unsafeNow(), JobResult.Failure,
        Some(JobResultMessage(s"${runningJob3.nodeId} is dead and max attempts has been reached")))

      leader.queuedJobs.values().asScala must containTheSameElementsAs(List(queuedJob2WithAttempt))
      jobStore.queuedJobStore.values().asScala must containTheSameElementsAs(List(queuedJob2WithAttempt))
      jobStore.finishedJobStore must containTheSameElementsAs(List(finishedJob3))

      leader.runningJobs.values().asScala must containTheSameElementsAs(List(runningJob1))
      jobStore.runningJobStore.values().asScala must containTheSameElementsAs(List(runningJob1))
    }
  }
}
