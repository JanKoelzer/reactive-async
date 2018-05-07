package com.phaller.rasync

import lattice.Key

import scala.concurrent.OnCompleteRunnable
import scala.util.control.NonFatal

/**
 * Run a callback in a handler pool, if a value in a cell changes.
 * Call execute() to add the callback to the given HandlerPool.
 */
private[rasync] trait CallbackRunnable[K <: Key[V], V] extends Runnable with OnCompleteRunnable {
  /** The handler pool that runs the callback function. */
  val pool: HandlerPool

  /** The cell that awaits this callback. */
  val dependentCompleter: CellCompleter[K, V]

  /** The cell that triggers the callback. */
  val otherCell: Cell[K, V]

  /** The callback to be called. It retrieves an updated value of otherCell and returns an Outcome for dependentCompleter. */
  val valueCallback: V => Outcome[V]

  /** Add this CallbackRunnable to its handler pool. */
  def execute(): Unit

  /** Essentially, call the callback. */
  override def run(): Unit
}

/**
 * Run a callback concurrently, if a value in a cell changes.
 * Call execute() to add the callback to the given HandlerPool.
 */
private[rasync] trait ConcurrentCallbackRunnable[K <: Key[V], V] extends CallbackRunnable[K, V] {
  /** Add this CallbackRunnable to its handler pool such that it is run concurrently. */
  def execute(): Unit =
    if (otherCell.hasStagedValueFor(dependentCompleter.cell) != NoOutcome)
      try pool.execute(this)
      catch { case NonFatal(t) => pool reportFailure t }
}

/**
 * Run a callback sequentially (for a dependent cell), if a value in another cell changes.
 * Call execute() to add the callback to the given HandlerPool.
 */
private[rasync] trait SequentialCallbackRunnable[K <: Key[V], V] extends CallbackRunnable[K, V] {
  /**
   * Add this CallbackRunnable to its handler pool such that it is run sequentially.
   * All SequentialCallbackRunnables with the same `dependentCell` are executed sequentially.
   */
  def execute(): Unit =
    if (otherCell.hasStagedValueFor(dependentCompleter.cell) != NoOutcome)
      pool.scheduleSequentialCallback(this)
}

/**
 * To be run when `otherCell` gets its final update.
 * @param pool          The handler pool that runs the callback function
 * @param dependentCompleter The cell, that depends on `otherCell`.
 * @param otherCell     Cell that triggers this callback.
 * @param callback      Callback function that is triggered on an onNext event
 */
private[rasync] abstract class CompleteCallbackRunnable[K <: Key[V], V](
  override val pool: HandlerPool,
  override val dependentCompleter: CellCompleter[K, V], // needed to not call whenNext callback, if whenComplete callback exists.
  override val otherCell: Cell[K, V],
  val callback: V => Outcome[V])
  extends CallbackRunnable[K, V] {

  // must be filled in before running it
  var started: Boolean = false

  def run(): Unit = {
//    require(!started) // can't complete it twice
    started = true

    otherCell.getStagedValueFor(dependentCompleter.cell) match {
      case FinalOutcome(x) =>
        valueCallback(x) match {
          case FinalOutcome(v) =>
            dependentCompleter.putFinal(v) // callbacks will be removed by putFinal()
          case NextOutcome(v) =>
            dependentCompleter.putNext(v)
            dependentCompleter.cell.removeAllCallbacks(otherCell)
          case NoOutcome =>
            dependentCompleter.cell.removeAllCallbacks(otherCell)
        }
      case _ => /* This is a whenCompleteDependency. Ignore any non-final values of otherCell. */
    }
  }
}

private[rasync] class CompleteConcurrentCallbackRunnable[K <: Key[V], V](override val pool: HandlerPool, override val dependentCompleter: CellCompleter[K, V], override val otherCell: Cell[K, V], override val valueCallback: V => Outcome[V])
  extends CompleteCallbackRunnable[K, V](pool, dependentCompleter, otherCell, valueCallback) with ConcurrentCallbackRunnable[K, V]

private[rasync] class CompleteSequentialCallbackRunnable[K <: Key[V], V](override val pool: HandlerPool, override val dependentCompleter: CellCompleter[K, V], override val otherCell: Cell[K, V], override val valueCallback: V => Outcome[V])
  extends CompleteCallbackRunnable[K, V](pool, dependentCompleter, otherCell, valueCallback) with SequentialCallbackRunnable[K, V]

 /* To be run when `otherCell` gets a final update.
 * @param pool          The handler pool that runs the callback function
 * @param dependentCompleter The cell, that depends on `otherCell`.
 * @param otherCell     Cell that triggers this callback.
 * @param callback      Callback function that is triggered on an onNext event
 */
private[rasync] abstract class NextCallbackRunnable[K <: Key[V], V](
  override val pool: HandlerPool,
  override val dependentCompleter: CellCompleter[K, V], // needed to not call whenNext callback, if whenComplete callback exists.
  override val otherCell: Cell[K, V],
  val callback: V => Outcome[V])
  extends CallbackRunnable[K, V] {

  def run(): Unit = {
    otherCell.getStagedValueFor(dependentCompleter.cell) match {
      case Outcome(x, isFinal) =>
        valueCallback(x) match {
          case NextOutcome (v) =>
            dependentCompleter.putNext (v)
          case FinalOutcome (v) =>
            dependentCompleter.putFinal (v)
          case _ => /* do nothing, the value of */
        }
        if (isFinal) dependentCompleter.cell.removeAllCallbacks(otherCell)
    }
  }
}

private[rasync] class NextConcurrentCallbackRunnable[K <: Key[V], V](override val pool: HandlerPool, override val dependentCompleter: CellCompleter[K, V], override val otherCell: Cell[K, V], override val valueCallback: V => Outcome[V])
  extends NextCallbackRunnable[K, V](pool, dependentCompleter, otherCell, valueCallback) with ConcurrentCallbackRunnable[K, V]

private[rasync] class NextSequentialCallbackRunnable[K <: Key[V], V](override val pool: HandlerPool, override val dependentCompleter: CellCompleter[K, V], override val otherCell: Cell[K, V], override val valueCallback: V => Outcome[V])
  extends NextCallbackRunnable[K, V](pool, dependentCompleter, otherCell, valueCallback) with SequentialCallbackRunnable[K, V]
