package com.phaller.rasync

import scala.util.Try
import lattice.{ DefaultKey, Key, Updater }

/**
 * Interface trait for programmatically completing a cell. Analogous to `Promise[V]`.
 */
trait CellCompleter[K <: Key[V], V] {

  /**
   * The cell associated with this completer.
   */
  val cell: Cell[K, V]

  /** A method to call */
  private[rasync] val init: (Cell[K, V]) => Outcome[V]

  /**
   * Update `this` cells value with `x` and freeze it.
   * The new value of `this` cell is determined by its updater.
   */
  def putFinal(x: V): Unit

  /**
   * Update `this` cells value with `x`.
   * The new value of `this` cell is determined by its updater.
   */
  def putNext(x: V): Unit

  /**
   * Update `this` cells value with `x`. If `isFinal` is `true`, the
   * cell will be frozen.
   * The new value of `this` cell is determined by its updater.
   */
  def put(x: V, isFinal: Boolean): Unit

  private[rasync] def tryNewState(value: V): Boolean
  def tryComplete(value: Try[V], dontCall: Option[Seq[Cell[K, V]]]): Boolean

  private[rasync] def removeCompleteDepentCell(cell: Cell[K, V]): Unit
  private[rasync] def removeNextDepentCell(cell: Cell[K, V]): Unit

  /**
   * Run code for `this` cell sequentially.
   * @return The result of `f`.
   */
  def sequential(f: () => _): Unit
}

object CellCompleter {

  /**
   * Create a completer for a cell holding values of type `V`
   * given a `HandlerPool` and a `Key[V]`.
   */
  def apply[K <: Key[V], V](key: K, init: (Cell[K, V]) => Outcome[V] = (_: Cell[K, V]) => NoOutcome)(implicit updater: Updater[V], pool: HandlerPool): CellCompleter[K, V] = {
    val impl = new CellImpl[K, V](pool, key, updater, init)
    pool.register(impl)
    impl
  }

  /**
   * Create a cell completer which is already completed with value `result`.
   *
   * Note: there is no `K` type parameter, since we always use type
   * `DefaultKey[V]`, no other key would make sense.
   */
  def completed[V](result: V)(implicit updater: Updater[V], pool: HandlerPool): CellCompleter[DefaultKey[V], V] = {
    val impl = new CellImpl[DefaultKey[V], V](pool, new DefaultKey[V], updater, _ => NoOutcome)
    pool.register(impl)
    impl.putFinal(result)
    impl
  }

}
