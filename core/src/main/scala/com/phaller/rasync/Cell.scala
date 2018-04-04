package com.phaller.rasync

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ CountDownLatch, ExecutionException }

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import lattice.{DefaultKey, Key, NotMonotonicException, PartialOrderingWithBottom, Updater}

trait Cell[K <: Key[V], V] {
  private[rasync] val completer: CellCompleter[K, V]

  def key: K

  /**
   * Returns the current value of `this` `Cell`.
   *
   * Note that this method may return non-deterministic values. To ensure
   * deterministic executions use the quiescence API of class `HandlerPool`.
   */
  def getResult(): V

  /** Start computations associated with this cell. */
  def trigger(): Unit

  def isComplete: Boolean

  /**
   * Adds a dependency on some `other` cell.
   *
   * Example:
   * {{{
   *   whenComplete(cell, {                   // when `cell` is completed
   *     case Impure => FinalOutcome(Impure)  // if the final value of `cell` is `Impure`, `this` cell is completed with value `Impure`
   *     case _ => NoOutcome
   *   })
   * }}}
   *
   * @param other  Cell that `this` Cell depends on.
   * @param valueCallback  Callback that receives the final value of `other` and returns an `Outcome` for `this` cell.
   */
  def whenComplete(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit
  def whenCompleteSequential(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit

  /**
   * Adds a dependency on some `other` cell.
   *
   * Example:
   * {{{
   *   whenNext(cell, {                       // when the next value is put into `cell`
   *     case Impure => FinalOutcome(Impure)  // if the next value of `cell` is `Impure`, `this` cell is completed with value `Impure`
   *     case _ => NoOutcome
   *   })
   * }}}
   *
   * @param other  Cell that `this` Cell depends on.
   * @param valueCallback  Callback that receives the new value of `other` and returns an `Outcome` for `this` cell.
   */
  def whenNext(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit
  def whenNextSequential(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit

  /**
   * Adds a dependency on some `other` cell.
   *
   * Example:
   * {{{
   *   when(cell, (x, isFinal) => x match { // when the next value or final value is put into `cell`
   *     case (_, Impure) => FinalOutcome(Impure)  // if the next value of `cell` is `Impure`, `this` cell is completed with value `Impure`
   *     case (true, Pure) => FinalOutcome(Pure)// if the final value of `cell` is `Pure`, `this` cell is completed with `Pure`.
   *     case _ => NoOutcome
   *   })
   * }}}
   *
   * @param other  Cell that `this` Cell depends on.
   * @param valueCallback  Callback that receives the new value of `other` and returns an `Outcome` for `this` cell.
   */
  def when(other: Cell[K, V], valueCallback: (V, Boolean) => Outcome[V]): Unit
  def whenSequential(other: Cell[K, V], valueCallback: (V, Boolean) => Outcome[V]): Unit

  def zipFinal(that: Cell[K, V]): Cell[DefaultKey[(V, V)], (V, V)]

  // internal API

  // Schedules execution of `callback` when next intermediate result is available.
  private[rasync] def onNext[U](callback: Try[V] => U): Unit //(implicit context: ExecutionContext): Unit

  // Schedules execution of `callback` when completed with final result.
  private[rasync] def onComplete[U](callback: Try[V] => U): Unit

  // Only used in tests.
  private[rasync] def waitUntilNoDeps(): Unit

  // Only used in tests.
  private[rasync] def waitUntilNoNextDeps(): Unit

  private[rasync] def tasksActive(): Boolean
  private[rasync] def setTasksActive(): Boolean

  private[rasync] def numTotalDependencies: Int
  private[rasync] def numNextDependencies: Int
  private[rasync] def numCompleteDependencies: Int

  private[rasync] def numNextCallbacks: Int
  private[rasync] def numCompleteCallbacks: Int

  private[rasync] def addCompleteDependentCell(dependentCell: Cell[K, V]): Unit
  private[rasync] def addNextDependentCell(dependentCell: Cell[K, V]): Unit

  private[rasync] def removeDependentCell(dependentCell: Cell[K, V]): Unit

  private[rasync] def resolveWithValue(value: V): Unit
  def completeCellDependencies: Seq[Cell[K, V]]
  def totalCellDependencies: Seq[Cell[K, V]]
  def isIndependent(): Boolean

  def removeCompleteCallbacks(cell: Cell[K, V]): Unit
  def removeNextCallbacks(cell: Cell[K, V]): Unit

  private[rasync] def removeAllCallbacks(cell: Cell[K, V]): Unit
  private[rasync] def removeAllCallbacks(cells: Seq[Cell[K, V]]): Unit

  private[rasync] def updateDeps(): Unit
  private[rasync] def resultChanged(dependentCell: Cell[K, V]): Outcome[V]

  def isADependee(): Boolean
}

object Cell {

  def completed[V](result: V)(implicit updater: Updater[V], pool: HandlerPool): Cell[DefaultKey[V], V] = {
    val completer = CellCompleter.completed(result)(updater, pool)
    completer.cell
  }

  def sequence[K <: Key[V], V](in: List[Cell[K, V]])(implicit pool: HandlerPool): Cell[DefaultKey[List[V]], List[V]] = {
    implicit val updater: Updater[List[V]] = Updater.partialOrderingToUpdater(PartialOrderingWithBottom.trivial[List[V]])
    val completer =
      CellCompleter[DefaultKey[List[V]], List[V]](new DefaultKey[List[V]])
    in match {
      case List(c) =>
        c.onComplete {
          case Success(x) =>
            completer.putFinal(List(x))
          case f @ Failure(_) =>
            completer.tryComplete(f.asInstanceOf[Failure[List[V]]])
        }
      case c :: cs =>
        val fst = in.head
        fst.onComplete {
          case Success(x) =>
            val tailCell = sequence(in.tail)
            tailCell.onComplete {
              case Success(xs) =>
                completer.putFinal(x :: xs)
              case f @ Failure(_) =>
                completer.tryComplete(f)
            }
          case f @ Failure(_) =>
            completer.tryComplete(f.asInstanceOf[Failure[List[V]]])
        }
    }
    completer.cell
  }

}

/* State of a cell that is not yet completed.
 *
 * This is not a case class, since it is important that equality is by-reference.
 *
 * @param res       current intermediate result (optional)
 * @param deps      dependent Cells + indication if value changed since last read
 * @param callbacks list of registered call-back runnables
 */
private class State[K <: Key[V], V](
     val res: V,
     val tasksActive: Boolean,
     val completeDependentCells: Map[Cell[K, V], Boolean],
     val completeCallbacks: Map[Cell[K, V], List[CompleteCallbackRunnable[K, V]]],
     val nextDependentCells: Map[Cell[K, V], Boolean],
     val nextCallbacks: Map[Cell[K, V], List[NextCallbackRunnable[K, V]]])

private object State {
  def empty[K <: Key[V], V](updater: Updater[V]): State[K, V] =
    new State[K, V](updater.bottom, false, Map(), Map(), Map(), Map())
}

private class CellImpl[K <: Key[V], V](pool: HandlerPool, val key: K, updater: Updater[V], val init: (Cell[K, V]) => Outcome[V]) extends Cell[K, V] with CellCompleter[K, V] {

  override val completer: CellCompleter[K, V] = this.asInstanceOf[CellCompleter[K, V]]

  implicit val ctx = pool

  private val nodepslatch = new CountDownLatch(1)
  private val nonextdepslatch = new CountDownLatch(1)

  /* Contains a value either of type
   * (a) `Try[V]`      for the final result, or
   * (b) `State[K,V]`  for an incomplete state.
   *
   * Assumes that dependencies need to be kept until a final result is known.
   *
   * This is only true, if Updater.ignoreIfFinal==true, because we miss
   * IllegalStateExceptions.
   */
  private val state = new AtomicReference[AnyRef](State.empty[K, V](updater))

  // `CellCompleter` and corresponding `Cell` are the same run-time object.
  override def cell: Cell[K, V] = this

  override def getResult(): V = state.get() match {
    case finalRes: Try[V] =>
      finalRes match {
        case Success(result) => result
        case Failure(err) => throw new IllegalStateException(err)
      }
    case raw: State[K, V] => raw.res
  }

  override def trigger(): Unit = {
    pool.triggerExecution(this)
  }

  override def isComplete: Boolean = state.get match {
    case _: Try[_] => true
    case _ => false
  }

  override def putFinal(x: V): Unit = {
    val res = tryComplete(Success(x))
    if (!res)
      throw new IllegalStateException("Cell already completed.")
  }

  override def putNext(x: V): Unit = {
    val res = tryNewState(x)
    if (!res)
      throw new IllegalStateException("Cell already completed.")
  }

  override def put(x: V, isFinal: Boolean): Unit = {
    if (isFinal) putFinal(x)
    else putNext(x)
  }

  def zipFinal(that: Cell[K, V]): Cell[DefaultKey[(V, V)], (V, V)] = {
    implicit val theUpdater: Updater[V] = updater
    val completer =
      CellCompleter[DefaultKey[(V, V)], (V, V)](new DefaultKey[(V, V)])(Updater.pair(updater), pool)
    this.onComplete {
      case Success(x) =>
        that.onComplete {
          case Success(y) =>
            completer.putFinal((x, y))
          case f @ Failure(_) =>
            completer.tryComplete(f.asInstanceOf[Try[(V, V)]])
        }
      case f @ Failure(_) =>
        completer.tryComplete(f.asInstanceOf[Try[(V, V)]])
    }
    completer.cell
  }

  private[this] def currentState(): State[K, V] =
    state.get() match {
      case finalRes: Try[_] => // completed with final result
        null
      case pre: State[_, _] => // not completed
        pre.asInstanceOf[State[K, V]]
    }

  override private[rasync] def numCompleteDependencies: Int = {
    val current = currentState()
    if (current == null) 0
    else current.completeCallbacks.keys.size
  }

  override private[rasync] def numNextDependencies: Int = {
    val current = currentState()
    if (current == null) 0
    else current.nextCallbacks.keys.size
  }

  override private[rasync] def numTotalDependencies: Int = {
    val current = currentState()
    if (current == null) 0
    else (current.completeCallbacks.keys ++ current.nextCallbacks.keys).size
  }

  override def completeCellDependencies: Seq[Cell[K, V]] = {
    state.get() match {
      case finalRes: Try[_] => // completed with final result
        Seq[Cell[K, V]]()
      case pre: State[_, _] => // not completed
        val current = pre.asInstanceOf[State[K, V]]
        current.completeCallbacks.keys.toSeq
    }
  }

  override def totalCellDependencies: Seq[Cell[K, V]] = {
    state.get() match {
      case finalRes: Try[_] => // completed with final result
        Seq[Cell[K, V]]()
      case pre: State[_, _] => // not completed
        val current = pre.asInstanceOf[State[K, V]]
        (current.completeCallbacks.keys ++ current.nextCallbacks.keys).toSeq
    }
  }

  override def isIndependent(): Boolean = {
    state.get() match {
      case finalRes: Try[_] => // completed with final result
        true
      case pre: State[_, _] => // not completed
        val current = pre.asInstanceOf[State[K, V]]
        current.completeDependentCells.isEmpty && current.nextDependentCells.isEmpty
    }
  }

  override def numNextCallbacks: Int = {
    state.get() match {
      case finalRes: Try[_] => // completed with final result
        0
      case pre: State[_, _] => // not completed
        val current = pre.asInstanceOf[State[K, V]]
        current.nextCallbacks.values.size
    }
  }

  override def numCompleteCallbacks: Int = {
    state.get() match {
      case finalRes: Try[_] => // completed with final result
        0
      case pre: State[_, _] => // not completed
        val current = pre.asInstanceOf[State[K, V]]
        current.completeCallbacks.values.size
    }
  }

  override private[rasync] def resolveWithValue(value: V): Unit = {
    this.putFinal(value)
  }

  override def when(other: Cell[K, V], valueCallback: (V, Boolean) => Outcome[V]): Unit = {
    this.whenNext(other, valueCallback(_, false))
    this.whenComplete(other, valueCallback(_, true))
  }

  override def whenSequential(other: Cell[K, V], valueCallback: (V, Boolean) => Outcome[V]): Unit = {
    this.whenNextSequential(other, valueCallback(_, false))
    this.whenCompleteSequential(other, valueCallback(_, true))
  }

  override def whenNext(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit = {
    this.whenNext(other, valueCallback, sequential = false)
  }

  override def whenNextSequential(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit = {
    this.whenNext(other, valueCallback, sequential = true)
  }

  private def whenNext(other: Cell[K, V], valueCallback: V => Outcome[V], sequential: Boolean): Unit = {
    var success = false
    while (!success) {
      state.get() match {
        case finalRes: Try[_] => // completed with final result
          // do not add dependency
          // in fact, do nothing
          success = true

        case raw: State[_, _] => // not completed
          val current = raw.asInstanceOf[State[K, V]]
          val depRegistered =
            if (current.nextCallbacks.contains(other)) true
            else {
              val newCallback: NextDepRunnable[K, V] =
                if (sequential) new NextSequentialDepRunnable(pool, this, other, valueCallback)
                else new NextConcurrentDepRunnable(pool, this, other, valueCallback)

              val newState = current.nextCallbacks.contains(other) match {
                case true => new State(current.res, current.tasksActive, current.completeDependentCells, current.completeCallbacks, current.nextDependentCells, current.nextCallbacks + (other -> (newCallback :: current.nextCallbacks(other))))
                case false => new State(current.res, current.tasksActive, current.completeDependentCells, current.completeCallbacks, current.nextDependentCells, current.nextCallbacks + (other -> List(newCallback)))
              }
              state.compareAndSet(current, newState)
            }
          if (depRegistered) {
            success = true
            other.addNextDependentCell(this)
            pool.triggerExecution(other)
          }
      }
    }
  }

  override def whenComplete(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit = {
    this.whenComplete(other, valueCallback, false)
  }

  override def whenCompleteSequential(other: Cell[K, V], valueCallback: V => Outcome[V]): Unit = {
    this.whenComplete(other, valueCallback, true)
  }

  private def whenComplete(other: Cell[K, V], valueCallback: V => Outcome[V], sequential: Boolean): Unit = {
    var success = false
    while (!success) {
      state.get() match {
        case _: Try[_] => // completed with final result
          // do not add dependency
          // in fact, do nothing
          success = true

        case raw: State[_, _] => // not completed
          val current = raw.asInstanceOf[State[K, V]]
          val depRegistered =
            if (current.completeCallbacks.contains(other)) true
            else {
              val newCallback: CompleteDepRunnable[K, V] =
                if (sequential) new CompleteSequentialDepRunnable(pool, this, other, valueCallback)
                else new CompleteConcurrentDepRunnable(pool, this, other, valueCallback)

              val newState = current.completeCallbacks.contains(other) match {
                case true => new State(current.res, current.tasksActive, current.completeDependentCells, current.completeCallbacks + (other -> (newCallback :: current.completeCallbacks(other))), current.nextDependentCells, current.nextCallbacks )
                case false => new State(current.res, current.tasksActive, current.completeDependentCells, current.completeCallbacks + (other -> List(newCallback)), current.nextDependentCells, current.nextCallbacks)
              }
              state.compareAndSet(current, newState)
            }
          if (depRegistered) {
            success = true
            other.addCompleteDependentCell(this)
            pool.triggerExecution(other)
          }
      }
    }
  }

  override private[rasync] def addCompleteDependentCell(dependentCell: Cell[K, V]): Unit = {
    triggerOrAddCompleteDependentCell(dependentCell)
  }

  override private[rasync] def addNextDependentCell(dependentCell: Cell[K, V]): Unit = {
    triggerOrAddNextDependentCell(dependentCell)
  }

  /**
   * Called by 'putNext' and 'putFinal'. It will try to join the current state
   * with the new value by using the given updater and return the new value.
   * If 'current == v' then it will return 'v'.
   */
  private def tryJoin(current: V, next: V): V = {
    updater.update(current, next)
  }

  /**
   * Called by 'putNext' which will try creating a new state with some new value
   * and then set the new state. The function returns 'true' if it succeeds, 'false'
   * if it fails.
   */
  @tailrec
  private[rasync] final def tryNewState(value: V): Boolean = {
    state.get() match {
      case finalRes: Try[_] => // completed with final result already
        try {
          val finalResult = finalRes.asInstanceOf[Try[V]].get
          val newVal = tryJoin(finalResult, value)
          val res = finalRes == Success(newVal)
          res
        } catch {
          case _: NotMonotonicException[_] => false
        }
      case raw: State[_, _] => // not completed
        val current = raw.asInstanceOf[State[K, V]]
        val newVal = tryJoin(current.res, value)
        val newNextDeps = current.nextDependentCells.map(cb => (cb._1, true))
        if (current.res != newVal) {
          val newState = new State(newVal, current.tasksActive, current.completeDependentCells, current.completeCallbacks, newNextDeps, current.nextCallbacks)
          if (!state.compareAndSet(current, newState)) {
            tryNewState(value)
          } else {
            // CAS was successful, so there was a point in time where `newVal` was in the cell
            // every dependent cell should pull the new value
            newNextDeps.foreach(_._1.updateDeps())
            true
          }
        } else true
    }
  }

  /**
   * Called by `tryComplete` to store the resolved value and get the current state
   *  or `null` if it is already completed.
   */
  // TODO: take care of compressing root (as in impl.Promise.DefaultPromise)
  @tailrec
  private def tryCompleteAndGetState(v: Try[V]): AnyRef = {
    state.get() match {
      case current: State[_, _] =>
        val currentState = current.asInstanceOf[State[K, V]]
        val newVal = Success(tryJoin(currentState.res, v.get))
        if (state.compareAndSet(current, newVal))
          (currentState, newVal)
        else
          tryCompleteAndGetState(v)

      case finalRes: Try[_] => finalRes
    }
  }

  override def tryComplete(value: Try[V]): Boolean = {
    val resolved: Try[V] = resolveTry(value)

    // the only call to `tryCompleteAndGetState`
    val res = tryCompleteAndGetState(resolved) match {
      case finalRes: Try[_] => // was already complete
        val finalResult = finalRes.asInstanceOf[Try[V]].get
        val newVal = value.map(tryJoin(finalResult, _))
        val res = finalRes == newVal
        res

      case (pre: State[K, V], _) =>
        pre.completeDependentCells.foreach(_._1.updateDeps())
        pre.nextDependentCells.foreach(_._1.updateDeps())

        // we do not need to be informed of changes of other cells
        val others = pre.completeCallbacks.keys ++ pre.nextCallbacks.keys
        others.foreach(_.removeDependentCell(this))

        true
    }
    if (res) {
      pool.deregister(this)
    }
    res
  }

  @tailrec
  private[rasync] def removeDependentCell(dependentCell: Cell[K, V]): Unit = state.get match {
    case pre: State[_, _] =>
      val current = pre.asInstanceOf[State[K, V]]
      val newCompleteDeps = current.completeDependentCells - cell
      val newNextDeps = current.nextDependentCells - cell
      val newState = new State(current.res, current.tasksActive, newCompleteDeps, current.completeCallbacks, newNextDeps, current.nextCallbacks)
      if (!state.compareAndSet(current, newState))
        removeDependentCell(dependentCell)

    case _=> /* do nothing */

  }

  @tailrec
  override private[rasync] final def removeDep(cell: Cell[K, V]): Unit = {
    state.get() match {
      case pre: State[_, _] =>
        val current = pre.asInstanceOf[State[K, V]]
        val newDeps = current.completeDependentCells - cell

        val newState = new State(current.res, current.tasksActive, newDeps, current.completeCallbacks, current.nextDependentCells, current.nextCallbacks)
        if (!state.compareAndSet(current, newState))
          removeDep(cell)
        else if (newDeps.isEmpty)
          nodepslatch.countDown()

      case _ => /* do nothing */
    }
  }

  @tailrec
  override private[rasync] final def removeNextDep(cell: Cell[K, V]): Unit = {
    state.get() match {
      case pre: State[_, _] =>
        val current = pre.asInstanceOf[State[K, V]]
        val newNextDeps = current.nextDependentCells - cell

        val newState = new State(current.res, current.tasksActive, current.completeDependentCells, current.completeCallbacks, newNextDeps, current.nextCallbacks)
        if (!state.compareAndSet(current, newState))
          removeNextDep(cell)
        else if (newNextDeps.isEmpty)
          nonextdepslatch.countDown()

      case _ => /* do nothing */
    }
  }

  @tailrec
  override final def removeCompleteCallbacks(cell: Cell[K, V]): Unit = {
    state.get() match {
      case pre: State[_, _] =>
        val current = pre.asInstanceOf[State[K, V]]
        val newCompleteCallbacks = current.completeCallbacks - cell

        val newState = new State(current.res, current.tasksActive, current.completeDependentCells, newCompleteCallbacks, current.nextDependentCells, current.nextCallbacks)
        if (!state.compareAndSet(current, newState))
          removeCompleteCallbacks(cell)
      case _ => /* do nothing */
    }
  }

  @tailrec
  override final def removeNextCallbacks(cell: Cell[K, V]): Unit = {
    state.get() match {
      case pre: State[_, _] =>
        val current = pre.asInstanceOf[State[K, V]]
        val newNextCallbacks = current.nextCallbacks - cell

        val newState = new State(current.res, current.tasksActive, current.completeDependentCells, current.completeCallbacks, current.nextDependentCells, newNextCallbacks)
        if (!state.compareAndSet(current, newState))
          removeNextCallbacks(cell)
      case _ => /* do nothing */
    }
  }

  @tailrec
  override private[rasync] final def removeAllCallbacks(cell: Cell[K, V]): Unit = {
    state.get() match {
      case pre: State[_, _] =>
        val current = pre.asInstanceOf[State[K, V]]
        val newNextCallbacks = current.nextCallbacks - cell
        val newCompleteCallbacks = current.completeCallbacks - cell

        val newState = new State(current.res, current.tasksActive, current.completeDependentCells, newCompleteCallbacks, current.nextDependentCells, newNextCallbacks)
        if (!state.compareAndSet(current, newState))
          removeAllCallbacks(cell)
      case _ => /* do nothing */
    }
  }

  @tailrec
  override private[rasync] final def removeAllCallbacks(cells: Seq[Cell[K, V]]): Unit = {
    state.get() match {
      case pre: State[_, _] =>
        val current = pre.asInstanceOf[State[K, V]]
        val newNextCallbacks = current.nextCallbacks -- cells
        val newCompleteCallbacks = current.completeCallbacks -- cells

        val newState = new State(current.res, current.tasksActive, current.completeDependentCells, newCompleteCallbacks, current.nextDependentCells, newNextCallbacks)
        if (!state.compareAndSet(current, newState))
          removeAllCallbacks(cells)
      case _ => /* do nothing */
    }
  }

  override private[rasync] def waitUntilNoDeps(): Unit = {
    nodepslatch.await()
  }

  override private[rasync] def waitUntilNoNextDeps(): Unit = {
    nonextdepslatch.await()
  }

  override private[rasync] def tasksActive() = state.get() match {
    case _: Try[_] => false
    case s: State[_, _] => s.tasksActive
  }

  /**
   * Mark this cell as "running".
   *
   * @return Returns true, iff the cell's status changed (i.e. it had not been running before).
   */
  @tailrec
  override private[rasync] final def setTasksActive(): Boolean = state.get() match {
    case pre: State[_, _] =>
      if (pre.tasksActive)
        false
      else {
        val current = pre.asInstanceOf[State[K, V]]
        val newState = new State(current.res, true, current.completeDependentCells, current.completeCallbacks, current.nextDependentCells, current.nextCallbacks)
        if (!state.compareAndSet(current, newState)) setTasksActive()
        else !pre.tasksActive
      }
    case _ => false
  }

  // Schedules execution of `callback` when next intermediate result is available.
  override private[rasync] def onNext[U](callback: Try[V] => U): Unit = {
    val runnable = new NextConcurrentCallbackRunnable[K, V](pool, null, this, callback) // NULL indicates that no cell is waiting for this callback.
    triggerOrAddNextDependentCell(runnable)
  }

  // Schedules execution of `callback` when completed with final result.
  override def onComplete[U](callback: Try[V] => U): Unit = {
    val runnable = new CompleteConcurrentCallbackRunnable[K, V](pool, null, this, callback) // NULL indicates that no cell is waiting for this callback.
    triggerOrAddCompleteDependentCell(runnable)
  }

  /**
   * Tries to add the callback, if already completed, it dispatches the callback to be executed.
   *  Used by `onComplete()` to add callbacks to a promise and by `link()` to transfer callbacks
   *  to the root promise when linking two promises together.
   */
  @tailrec
  private def triggerOrAddCompleteDependentCell(dependentCell: Cell[K, V]): Unit = state.get() match {
    case r: Try[_] =>
      dependentCell.updateDeps()
    // case _: DefaultPromise[_] => compressedRoot().triggerOrAddCompleteDependentCell(runnable)
    case pre: State[_, _] =>
      // assemble new state
      val current = pre.asInstanceOf[State[K, V]]
      val newState = new State(current.res, current.tasksActive, current.completeDependentCells + (dependentCell -> false), current.completeCallbacks, current.nextDependentCells, current.nextCallbacks)
      if (!state.compareAndSet(pre, newState))
        triggerOrAddCompleteDependentCell(dependentCell)
  }

  private[rasync] def resultChanged(dependentCell: Cell[K, V]): Outcome[V] = state.get() match {
    case r: Try[_] =>
      FinalOutcome(r.asInstanceOf[Try[V]].get)
    case pre: State[_, _] =>
      // assemble new state
      val current = pre.asInstanceOf[State[K, V]]
      try
        if (current.nextDependentCells(dependentCell))
          NextOutcome(current.res)
        else
          NoOutcome
      catch {
        case _: NoSuchElementException =>
          throw new Exception(s"$dependentCell asked for a value of $this but was not registered as dependent cell.")
      }
  }

  /**
   * Tries to add the callback, if already completed, it dispatches the callback to be executed.
   *  Used by `onNext()` to add callbacks to a promise and by `link()` to transfer callbacks
   *  to the root promise when linking two promises together.
   */
  @tailrec
  private def triggerOrAddNextDependentCell(dependentCell: Cell[K, V]): Unit =
    state.get() match {
      case r: Try[_] =>
        dependentCell.updateDeps()
      // case _: DefaultPromise[_] => compressedRoot().triggerOrAddCompleteDependentCell(runnable)
      case pre: State[_, _] =>
        // assemble new state
        val current = pre.asInstanceOf[State[K, V]]
        val hasChanged = current.res != updater.bottom
        val newState = new State(current.res, current.tasksActive, current.completeDependentCells, current.completeCallbacks, current.nextDependentCells + (dependentCell -> hasChanged), current.nextCallbacks)
        if (!state.compareAndSet(pre, newState))
          triggerOrAddNextDependentCell(dependentCell)
        else if (hasChanged) dependentCell.updateDeps()
    }

  // copied from object `impl.Promise`
  private def resolveTry[T](source: Try[T]): Try[T] = source match {
    case Failure(t) => resolver(t)
    case _ => source
  }

  // copied from object `impl.Promise`
  private def resolver[T](throwable: Throwable): Try[T] = throwable match {
    case t: scala.runtime.NonLocalReturnControl[_] => Success(t.value.asInstanceOf[T])
    case t: scala.util.control.ControlThrowable => Failure(new ExecutionException("Boxed ControlThrowable", t))
    case t: InterruptedException => Failure(new ExecutionException("Boxed InterruptedException", t))
    case e: Error => Failure(new ExecutionException("Boxed Error", e))
    case t => Failure(t)
  }

  override private[rasync] def updateDeps(): Unit = state.get() match {
    case pre: State[_, _] =>
      val current = pre.asInstanceOf[State[K, V]]

      // TODO we should not simply call callback.execute() but instead avoid HandlerPool.cellsNotDone -> Queue altogether AND/OR: SequentialCallback.run() could use synchronized(dependentCell) {…}

      current.completeCallbacks.foreach { case (other, callbacks) =>
        other.resultChanged(this) match {
          case _: FinalOutcome[_] =>
            // TODO In this case, callbacks need to be removed!
            callbacks.foreach(callback => callback.execute())
          case _ => /* Do nothing for non-final values of `other`. */
        }
      }

      current.nextCallbacks.foreach { case (other, callbacks) =>
        other.resultChanged(this) match {
          case Outcome(_, _) =>
            callbacks.foreach(callback => callback.execute())
          case NoOutcome => /* Do nothing for NoOutcome */
        }
      }

    case _ => /* we are final already. TODO Do we need to do something? Probl. yes, to produce IllegalStateExceptions. */

  }

  /**
   * Checks if this cell is a dependee of some other cells. This is true if some cells called
   * whenNext[Sequential / Complete](thisCell, f)
   * @return True if some cells depends on this one, false otherwise
   */
  override def isADependee(): Boolean = {
    numCompleteCallbacks > 0 || numNextCallbacks > 0
  }

}
