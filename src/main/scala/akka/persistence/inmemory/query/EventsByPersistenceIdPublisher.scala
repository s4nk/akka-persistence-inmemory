/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.persistence.inmemory
package query

import akka.actor.ActorLogging
import akka.persistence.query.EventEnvelope
import akka.persistence.query.journal.leveldb.DeliveryBuffer
import akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import akka.stream.Materializer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }
import akka.stream.scaladsl.Sink

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, _ }

object EventsByPersistenceIdPublisher {
  sealed trait EventsByPersistenceIdPublisherCommand
  case object GetMessages extends EventsByPersistenceIdPublisherCommand
  case object BecomePolling extends EventsByPersistenceIdPublisherCommand
  case object DetermineSchedulePoll extends EventsByPersistenceIdPublisherCommand
}

class EventsByPersistenceIdPublisher(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, refreshInterval: FiniteDuration, maxBufferSize: Int, query: CurrentEventsByPersistenceIdQuery)(implicit ec: ExecutionContext, mat: Materializer) extends ActorPublisher[EventEnvelope] with DeliveryBuffer[EventEnvelope] with ActorLogging {
  import EventsByPersistenceIdPublisher._

  def determineSchedulePoll(): Unit = {
    if (buf.size < maxBufferSize && totalDemand > 0)
      context.system.scheduler.scheduleOnce(0.seconds, self, BecomePolling)
  }

  val checkPoller = context.system.scheduler.schedule(0.seconds, refreshInterval, self, DetermineSchedulePoll)

  def receive = active(Math.max(1, fromSequenceNr))

  def polling(fromSeqNr: Long): Receive = {
    case GetMessages ⇒
      query.currentEventsByPersistenceId(persistenceId, fromSeqNr, toSequenceNr)
        .take(maxBufferSize - buf.size)
        .runWith(Sink.seq)
        .map { xs ⇒
          buf = buf ++ xs
          val newFromSeqNr = xs.lastOption.map(_.sequenceNr + 1).getOrElse(fromSeqNr)
          deliverBuf()
          context.become(active(newFromSeqNr))
        }
        .recover {
          case t: Throwable ⇒
            log.error(t, "Error while polling eventsByPersistenceIds")
            onError(t)
            context.stop(self)
        }

    case Cancel ⇒ context.stop(self)
  }

  def active(fromSeqNr: Long): Receive = {
    case BecomePolling ⇒
      context.become(polling(fromSeqNr))
      self ! GetMessages

    case DetermineSchedulePoll if (buf.size - totalDemand) < 0 ⇒ determineSchedulePoll()

    case DetermineSchedulePoll                                 ⇒ deliverBuf()

    case Request(req)                                          ⇒ deliverBuf()

    case Cancel                                                ⇒ context.stop(self)
  }

  override def postStop(): Unit = {
    checkPoller.cancel()
    super.postStop()
  }
}
