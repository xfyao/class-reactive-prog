package kvstore

import java.util.concurrent.TimeUnit

import akka.actor.{Cancellable, Props, Actor, ActorRef}
import scala.concurrent.duration._
import scala.language.postfixOps

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher
  
  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]

  var resends = Map.empty[Long, Cancellable]
  
  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  
  /* TODO Behavior for the Replicator. */
  def receive: Receive = {
    case Replicate(key, value, id) =>
      val seq = nextSeq
      replica ! Snapshot(key, value, seq)
      acks += (seq -> (sender(), Replicate(key, value, id)))
      //schedule a retry after timeout
      println("Schedule a replicate task " + acks)
      val cancellable = context.system.scheduler.schedule(100 millisecond, 100 millisecond, replica, Snapshot(key, value, seq))
      resends += (seq -> cancellable)
    case SnapshotAck(key, seq) =>
      println("Finish a replicate task " + acks)
      //there could be duplicated ack. If so ignore.
      acks.get(seq) foreach { ack =>
        ack._1 ! Replicated(ack._2.key, ack._2.id)
        acks -= (seq)
        println("Cancel a replicate task " + acks)
        resends.get(seq).get.cancel()
        resends -= (seq)
      }
  }

}
