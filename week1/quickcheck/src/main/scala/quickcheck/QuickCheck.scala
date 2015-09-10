package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("min2") = forAll { (a: Int, b: Int) =>
    val h1 = insert(a, empty)
    val h2 = insert(b, h1)
    findMin(h2) == math.min(a, b)
  }

  property("delete") = forAll { a: Int =>
    val h1 = insert(a, empty)
    val h2 = deleteMin(h1)
    isEmpty(h2)
  }

  property("gen1") = forAll { h: H =>
    val m = if (isEmpty(h)) 0 else findMin(h)
    findMin(insert(m, h))==m
  }

  property("melding") = forAll { (h1: H, h2: H) =>
    val h = meld(h1, h2)
    findMin(h)== math.min(findMin(h1), findMin(h2))
  }

  property("simpleF&D") = forAll {
    (a: Int, b: Int) =>
      val h1 = insert(a, empty)
      val h2 = insert(b, h1)
      val h3 = deleteMin(h2)
      findMin(h3) == math.max(a, b)

  }


  property("find&delete") = forAll { h: H =>
    val sorted = getAndDeleteMin(h)
    isOrdered(sorted)
  }

  property("melding&find&delete") = forAll { (h1: H, h2: H) =>
    val h = meld(h1, h2)
    val sorted_all = getAndDeleteMin(h)
    val sorted1 = getAndDeleteMin(h1)
    val sorted2 = getAndDeleteMin(h2)
    val sorted12 = (sorted1 ::: sorted2).sorted
    sorted_all == sorted12
  }

  def getAndDeleteMin(h: H): List[Int] = {
    if(isEmpty(h)) Nil
    else {
      val a = findMin(h)
      a :: getAndDeleteMin(deleteMin(h))
    }
  }

  def isOrdered(l:List[Int]): Boolean = l match {
    case Nil => true
    case x :: Nil => true
    case x :: xs => x <= xs.head && isOrdered(xs)
  }


  lazy val genHeap: Gen[H] = for {
    k <- arbitrary[Int]
    m <- oneOf(const(empty), genHeap)
  } yield insert(k, m)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)


}
