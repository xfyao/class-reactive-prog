package calculator

import java.lang.Math

object Polynomial {
  def computeDelta(a: Signal[Double], b: Signal[Double],
      c: Signal[Double]): Signal[Double] = {
    Var(b()*b() - 4*a()*c())
  }

  def computeSolutions(a: Signal[Double], b: Signal[Double],
      c: Signal[Double], delta: Signal[Double]): Signal[Set[Double]] = {

    val delta = computeDelta(a, b, c)
    Var(delta() match {
      case x if(x>0) => Set((- b () + Math.sqrt (delta () ) ) / (2 * a () ), (- b () - Math.sqrt (delta () ) ) / (2 * a () ) )
      case 0 => Set((-b() / (2 * a())))
      case _ => Set()
    })
  }
}
