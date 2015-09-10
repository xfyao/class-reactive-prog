/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    
    case x:Operation => root.forward(x)
    case GC => {

      val newRoot = createRoot
      context.become(garbageCollecting(newRoot))
      root ! CopyTo(newRoot)

    }

  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case x:Operation => pendingQueue = pendingQueue.enqueue(x)
    case GC => ()
    case CopyFinished => {
      root = newRoot
      while(!pendingQueue.isEmpty) {
        val (element, newQueue) = pendingQueue.dequeue
        root.forward(element)
        pendingQueue = newQueue
      }
      context.become(normal)
      sender() ! PoisonPill
    }
  }

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Insert(requester, id, _elem) => _elem compare elem match {
      case 0 => removed = false; requester ! OperationFinished(id)
      case 1 => subtrees.contains(Right) match {
          case true => subtrees.get(Right).get ! Insert(requester, id, _elem)
          case false => subtrees += (Right -> context.actorOf(BinaryTreeNode.props(_elem, initiallyRemoved = false))); requester ! OperationFinished(id)
      }
      case -1 => subtrees.contains(Left) match {
        case true => subtrees.get(Left).get ! Insert(requester, id, _elem)
        case false => subtrees += (Left -> context.actorOf(BinaryTreeNode.props(_elem, initiallyRemoved = false))); requester ! OperationFinished(id)
      }
    }

    case Remove(requester, id, _elem) => _elem compare elem match {
      case 0 => removed = true; requester ! OperationFinished(id)
      case 1 => subtrees.contains(Right) match {
        case true => subtrees.get(Right).get ! Remove(requester, id, _elem)
        case false => requester ! OperationFinished(id)
      }
      case -1 => subtrees.contains(Left) match {
        case true => subtrees.get(Left).get ! Remove(requester, id, _elem)
        case false => requester ! OperationFinished(id)
      }
    }

    case Contains(requester, id, _elem) => _elem compare elem match {
      case 0 => requester ! ContainsResult(id, !removed)
      case 1 => subtrees.contains(Right) match {
        case true => subtrees.get(Right).get ! Contains(requester, id, _elem)
        case false => requester ! ContainsResult(id, false)
      }
      case -1 => subtrees.contains(Left) match {
        case true => subtrees.get(Left).get ! Contains(requester, id, _elem)
        case false => requester ! ContainsResult(id, false)
      }
    }

    case CopyTo(treeNode) => {
      context.become(copying(subtrees.values.toSet, false))
      subtrees.values.foreach(x => x ! CopyTo(treeNode))
      if(!removed) treeNode ! Insert(self, -1, elem)
      else self ! OperationFinished(-1)

    }
  }


  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {

    case CopyFinished => {
      val _expected = expected - sender()
      //sender() ! PoisonPill
      //Thread.sleep(100)
      if(_expected.size == 0 && insertConfirmed) { context.parent ! CopyFinished}
      else context.become(copying(_expected, insertConfirmed))

    }
    case OperationFinished(id) => {
      val _insertConfirmed = true
      if(expected.size == 0 && _insertConfirmed) { context.parent ! CopyFinished}
      else context.become(copying(expected, _insertConfirmed))
    }

  }


}
