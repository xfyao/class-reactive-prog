package kvstore

import akka.actor._
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout
import scala.language.postfixOps

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */
  
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  var persistor = context.actorOf(persistenceProps)

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5) {
    case _ : Exception => SupervisorStrategy.restart
  }


  var acks = Map.empty[Long, ActorRef]
  var resends = Map.empty[Long, Cancellable]
  var errors = Map.empty[Long, Cancellable]
  var scores = Map.empty[Long, Set[ActorRef]]


  arbiter ! Join

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(key, value, id) => {
      kv += (key -> value)
      replicators foreach( r => {
        println("Replicator Insert " + r)
        r ! Replicate(key, Some(value), id)
      })
      persistor ! Persist(key, Some(value), id)
      acks += (id -> sender)

      val cancellable = context.system.scheduler.schedule(100 millisecond, 100 millisecond, persistor, Persist(key, Some(value), id))
      resends += (id -> cancellable)

      val cancellable_err = context.system.scheduler.scheduleOnce(1000 millisecond, sender, OperationFailed(id))
      errors += (id -> cancellable_err)

      val score = replicators + persistor
      scores += (id -> score)

      println("Initial Insert Scores " + scores)
      println("Persistence Actore " + persistor)

      //sender() ! OperationAck(id)
    }
    case Remove(key, id) => {
      kv -= (key)
      replicators foreach( r => {
        println("Replicator Remove " + r)
        r ! Replicate(key, None, id)
      })

      persistor ! Persist(key, None, id)
      acks += (id -> sender)

      val cancellable = context.system.scheduler.schedule(100 millisecond, 100 millisecond, persistor, Persist(key, None, id))
      resends += (id -> cancellable)

      val cancellable_err = context.system.scheduler.scheduleOnce(1000 millisecond, sender, OperationFailed(id))
      errors += (id -> cancellable_err)

      val score = replicators + persistor
      scores += (id -> score)

      println("Schedule a persis task")

      //sender() ! OperationAck(id)
    }
    case Persisted(key, id)  => {

      //println("PPpppppppp " + key + " " + id)
      var score = scores.get(id).get

      println("Persisted with score " + score)

      resends.get(id).get.cancel()
      resends -= (id)

      println("cancel a persist task")

      score -= persistor

      println("sender " + sender() + " vs persistor " + persistor)
      println("After persisted with score " + score)

      scores += (id -> score)

      if(score.isEmpty) {
          errors.get(id).get.cancel()
          errors -= (id)

          val _sender = acks.get(id).get
          _sender ! OperationAck(id)
          println("Persisted Ack")
          acks -= (id)
          scores -= id
      }

    }

    case Replicated(key, id) => {

      id match {
        case -1 => ()
        case _ =>
          var score = scores.get(id).get

          println("Replicated with score " + score)

          //resends.get(id).get.cancel()
          //resends -= (id)

          score -= sender()
          scores += (id -> score)

          if(score.isEmpty) {

              errors.get(id).get.cancel()
              errors -= (id)

              val _sender = acks.get(id).get
              _sender ! OperationAck(id)
              println("Replicated Ack")
              acks -= (id)
              scores -= id

          }
      }

    }
    case Get(key, id) => sender() ! GetResult(key, kv.get(key), id)

    case Replicas(replicas) => {

      // add replicators if new ones are coming

      replicas.foreach(
         replica => {

           // add new replicator if have
           val replicator = context.actorOf(Replicator.props(replica))
           if(!replica.equals(self) && !replicators.contains(replicator)) {
             secondaries += (replica -> replicator)
             replicators += replicator

             context.watch(replicator)

             kv.foreach(
               kve => replicator ! Replicate(kve._1, Some(kve._2), -1)
             )
           }
         }
      )

      // remove replictors if they are gone

      secondaries --= secondaries.filter(se => !replicas.contains(se._1)).map(se=> {
        println("terminate replicator " + se._2)
        replicators -= se._2
        se._2 ! PoisonPill
        se
      }).keys


      println("Replicas result " + replicators)
    }

    case Terminated(replicator) => {
      println("Enter Terminated " + replicator)



      scores = scores map (sm => {
        var score = sm._2
        println("Sssssss " + score)
        score -= replicator
        if (score.isEmpty) {
          println("Call self to finish ...")
          self ! Replicated("xxx", sm._1)
        }
        (sm._1 -> score)
      })

    }
  }


  var _seqCounter = 0L

  /* TODO Behavior for the replica role. */
  val replica: Receive = {
    case Get(key, id) => sender ! GetResult(key, kv.get(key), id)
    case Snapshot(key, value, seq) =>

      println("Snapshot in " + key + " " + value + " " + seq)
      seq.compareTo(_seqCounter) match {
        case 0 =>
          value match {
            case Some(v) => kv += (key -> v)
            case None => kv -= (key)
          }
          persistor ! Persist(key, value, seq)
          acks += (seq -> sender)

          val cancellable = context.system.scheduler.schedule(100 millisecond, 100 millisecond, persistor, Persist(key, value, seq))
          resends += (seq -> cancellable)

          _seqCounter += 1

        case 1 => ()

        case -1 => sender() ! SnapshotAck(key, seq)

      }
    case Persisted(key, seq) =>
      println("Persisted in " + key + " " + seq)
      val _sender = acks.get(seq).get
      _sender ! SnapshotAck(key, seq)
      acks -= (seq)

      resends.get(seq).get.cancel()
      resends -= (seq)
  }

}

c