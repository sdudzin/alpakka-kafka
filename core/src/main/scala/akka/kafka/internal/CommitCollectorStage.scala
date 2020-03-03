/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.internal

import akka.annotation.InternalApi
import akka.kafka.{CommitDelivery, CommitterSettings}
import akka.kafka.ConsumerMessage.{Committable, CommittableOffsetBatch}
import akka.stream._
import akka.stream.stage._

/**
 * INTERNAL API.
 *
 * Combined stage for committing incoming offsets in batches. Capable of emitting dynamic (reduced) size batch in case of
 * upstream failures. Support flushing on failure (for downstreams).
 */
@InternalApi
private[kafka] final class CommitCollectorStage(val committerSettings: CommitterSettings)
    extends GraphStage[FlowShape[Committable, CommittableOffsetBatch]] {

  val in: Inlet[Committable] = Inlet[Committable]("FlowIn")
  val out: Outlet[CommittableOffsetBatch] = Outlet[CommittableOffsetBatch]("FlowOut")
  val shape: FlowShape[Committable, CommittableOffsetBatch] = FlowShape(in, out)

  override def createLogic(
      inheritedAttributes: Attributes
  ): GraphStageLogic = {
    new CommitCollectorStageLogic(this, inheritedAttributes)
  }
}

private final class CommitCollectorStageLogic(
    stage: CommitCollectorStage,
    inheritedAttributes: Attributes
) extends TimerGraphStageLogic(stage.shape)
    with StageIdLogging {

  import CommitCollectorStage._
  import CommitTrigger._

  override protected def logSource: Class[_] = classOf[CommitCollectorStageLogic]

  // ---- initialization
  override def preStart(): Unit = {
    super.preStart()
    scheduleCommit()
    log.debug("CommitCollectorStage initialized")
  }

  /** Batches offsets until a commit is triggered. */
  private var offsetBatch: CommittableOffsetBatch = CommittableOffsetBatch.empty

  // ---- Consuming
  private def consume(offset: Committable): Unit = {
    log.debug("Consuming offset {}", offset)
    offsetBatch = offsetBatch.updated(offset)
    if (offsetBatch.batchSize >= stage.committerSettings.maxBatch) pushDownStream(BatchSize)(push)
    else tryPull(stage.in) // accumulating the batch
  }

  private def scheduleCommit(): Unit =
    scheduleOnce(CommitNow, stage.committerSettings.maxInterval)

  override protected def onTimer(timerKey: Any): Unit = timerKey match {
    case CommitCollectorStage.CommitNow => pushDownStream(Interval)(push)
  }

  private def pushDownStream(triggeredBy: TriggerdBy)(
      emission: (Outlet[CommittableOffsetBatch], CommittableOffsetBatch) => Unit
  ): Unit = {
    if (activeBatchInProgress) {
      log.debug("pushDownStream triggered by {}, outstanding batch {}", triggeredBy, offsetBatch)
      emission(stage.out, offsetBatch)
      offsetBatch = CommittableOffsetBatch.empty
    }
    scheduleCommit()
  }

  setHandler(
    stage.in,
    new InHandler {
      def onPush(): Unit = {
        consume(grab(stage.in))
      }

      override def onUpstreamFinish(): Unit = {
        if (noActiveBatchInProgress) {
          completeStage()
        } else {
          pushDownStream(UpstreamFinish)(emit[CommittableOffsetBatch])
          completeStage()
        }
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        log.debug("Received onUpstreamFailure with exception {}", ex)
        if (noActiveBatchInProgress) {
          failStage(ex)
        } else {
          commitAndPushWithFailure(ex)
        }
      }
    }
  )

  setHandler(
    stage.out,
    new OutHandler {
      def onPull(): Unit = if (!hasBeenPulled(stage.in)) {
        tryPull(stage.in)
      }
    }
  )

  private def commitAndPushWithFailure(ex: Throwable): Unit = {
    setKeepGoing(true)
    if (offsetBatch.isEmpty) {
      failStage(ex)
    } else {
      log.debug("committing batch in flight on failure {}", offsetBatch)
      val batchInFlight = offsetBatch
      offsetBatch.commitEmergency()
      offsetBatch = CommittableOffsetBatch.empty
      failStage(ex)
    }
  }

  override def postStop(): Unit = {
    log.debug("CommitCollectorStage stopped")
    super.postStop()
  }

  private def noActiveBatchInProgress: Boolean = offsetBatch.isEmpty
  private def activeBatchInProgress: Boolean = !offsetBatch.isEmpty
}

private[akka] object CommitCollectorStage {
  val CommitNow = "flowStageCommit"
}
