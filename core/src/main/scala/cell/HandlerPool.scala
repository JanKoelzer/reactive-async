package cell

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference

import immutability.V

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import lattice.{DefaultKey, Key, Lattice}
import org.opalj.graphs._

import scala.util.Success

/* Need to have reference equality for CAS.
 */
class PoolState(val handlers: List[() => Unit] = List(), val submittedTasks: Int = 0) {
  def isQuiescent(): Boolean =
    submittedTasks == 0
}

class HandlerPool(parallelism: Int = 8, unhandledExceptionHandler: Throwable => Unit = _.printStackTrace()) {

  @tailrec
  final protected[cell] def schedule[K <: Key[V], V](cell: Cell[K, V], task: Runnable): Unit = {
    // TODO Is this safe? What if a cell gets awaited right before the task is added?
    if (cellsAwaited.get().contains(cell)) execute(task)
    else {
      val oldTasks = tasksScheduled.get()
      val tasksForCell = oldTasks.getOrElse(cell, Seq()) ++ Seq(task) // Is the second Seq() needed? I guess not!
      val newTasks = oldTasks + (cell -> tasksForCell)
      if (!tasksScheduled.compareAndSet(oldTasks, newTasks)) {
        schedule(cell, task)
      }
    }
  }

  private val pool: ForkJoinPool = new ForkJoinPool(parallelism)

  private val poolState = new AtomicReference[PoolState](new PoolState)

  private val cellsNotDone = new AtomicReference[Map[Cell[_, _], Cell[_, _]]](Map())

  private val cellsAwaited = new AtomicReference[Map[Cell[_, _], Cell[_, _]]](Map())

  /** Whenever a cell gets awaited, run the mapped runnables for that cell. */
  // Maybe this could be merged with cellsNotDone or cellsAwaited?
  private val tasksScheduled = new AtomicReference[Map[Cell[_, _], Seq[Runnable]]](Map())

  def createCell[K <: Key[V], V](key: K, init: (Cell[K, V]) => WhenNextOutcome[V])(implicit lattice: Lattice[V]): Cell[K, V] = {
    CellCompleter(this, key, init)(lattice).cell
  }

  def createCell[K <: Key[V], V](key: K, init: V)(implicit lattice: Lattice[V]): Cell[K, V] = {
    val completer = CellCompleter(this, key, (_: Cell[K, V]) => NextOutcome(init))(lattice)
    completer.cell
  }

  def createCompletedCell[V](result: V)(implicit lattice: Lattice[V]): Cell[DefaultKey[V], V] = {
    CellCompleter.completed(this, result)(lattice).cell
  }

  @tailrec
  final def onQuiescent(handler: () => Unit): Unit = {
    val state = poolState.get()
    if (state.isQuiescent) {
      execute(new Runnable { def run(): Unit = handler() })
    } else {
      val newState = new PoolState(handler :: state.handlers, state.submittedTasks)
      val success = poolState.compareAndSet(state, newState)
      if (!success)
        onQuiescent(handler)
    }
  }

  private[cell] def register[K <: Key[V], V](cell: Cell[K, V]): Unit = {
    val registered = cellsNotDone.get()
    val newRegistered = registered + (cell -> cell)
    cellsNotDone.compareAndSet(registered, newRegistered)
  }

  private[cell] def deregister[K <: Key[V], V](cell: Cell[K, V]): Unit = {
    var success = false
    while (!success) {
      val registered = cellsNotDone.get()
      val newRegistered = registered - cell
      success = cellsNotDone.compareAndSet(registered, newRegistered)
    }
  }

  def quiescentIncompleteCells: Future[List[Cell[_, _]]] = {
    val p = Promise[List[Cell[_, _]]]
    this.onQuiescent { () =>
      val registered = this.cellsNotDone.get()
      p.success(registered.values.toList)
    }
    p.future
  }

  def whileQuiescentResolveCell[K <: Key[V], V]: Unit = {
    while (!cellsNotDone.get().isEmpty) {
      val fut = this.quiescentResolveCell
      Await.ready(fut, 15.minutes)
    }
  }

  def whileQuiescentResolveDefault[K <: Key[V], V]: Unit = {
    while (!cellsNotDone.get().isEmpty) {
      val fut = this.quiescentResolveDefaults
      Await.ready(fut, 15.minutes)
    }
  }

  def quiescentResolveCycles[K <: Key[V], V]: Future[Boolean] = {
    val p = Promise[Boolean]
    this.onQuiescent { () =>
      // Find one closed strongly connected component (cell)
      val registered: Seq[Cell[K, V]] = this.cellsNotDone.get().values.asInstanceOf[Iterable[Cell[K, V]]].toSeq
      println(registered.size)
      if (registered.nonEmpty) {
        val cSCCs = closedSCCs(registered, (cell: Cell[K, V]) => cell.cellDependencies)
        cSCCs.foreach(cSCC => resolveCycle(cSCC.asInstanceOf[Seq[Cell[K, V]]]))
      }
      p.success(true)
    }
    p.future
  }

  def quiescentResolveDefaults[K <: Key[V], V]: Future[Boolean] = {
    val p = Promise[Boolean]
    this.onQuiescent { () =>
      // Finds the rest of the unresolved cells
      val rest = this.cellsNotDone.get().values.asInstanceOf[Iterable[Cell[K, V]]].toSeq
      if (rest.nonEmpty) {
        resolveDefault(rest)
      }
      p.success(true)
    }
    p.future
  }

  def quiescentResolveCell[K <: Key[V], V]: Future[Boolean] = {
    val p = Promise[Boolean]
    this.onQuiescent { () =>
      // Find one closed strongly connected component (cell)
      val registered: Seq[Cell[K, V]] = this.cellsNotDone.get().values.asInstanceOf[Iterable[Cell[K, V]]].toSeq
      if (registered.nonEmpty) {
        val cSCCs = closedSCCs(registered, (cell: Cell[K, V]) => cell.cellDependencies)
        cSCCs.foreach(cSCC => resolveCycle(cSCC.asInstanceOf[Seq[Cell[K, V]]]))
      }
      // Finds the rest of the unresolved cells
      val rest = this.cellsNotDone.get().values.asInstanceOf[Iterable[Cell[K, V]]].toSeq
      if (rest.nonEmpty) {
        resolveDefault(rest)
      }
      p.success(true)
    }
    p.future
  }

  /**
   * Resolves a cycle of unfinished cells.
   */
  private def resolveCycle[K <: Key[V], V](cells: Seq[Cell[K, V]]): Unit = {
    val key = cells.head.key
    val result = key.resolve(cells)

    for ((c, v) <- result) c.resolveWithValue(v)
  }

  /**
   * Resolves a cell with default value.
   */
  private def resolveDefault[K <: Key[V], V](cells: Seq[Cell[K, V]]): Unit = {
    val key = cells.head.key
    val result = key.fallback(cells)

    for ((c, v) <- result) c.resolveWithValue(v)
  }

  // Shouldn't we use:
  //def execute(f : => Unit) : Unit =
  //  execute(new Runnable{def run() : Unit = f})

  protected[cell] def execute(fun: () => Unit): Unit =
    execute(new Runnable { def run(): Unit = fun() })

  protected[cell] def execute(task: Runnable): Unit = {
    // Submit task to the pool
    var submitSuccess = false
    while (!submitSuccess) {
      val state = poolState.get()
      val newState = new PoolState(state.handlers, state.submittedTasks + 1)
      submitSuccess = poolState.compareAndSet(state, newState)
    }

    // Run the task
    pool.execute(new Runnable {
      def run(): Unit = {
        try {
          task.run()
        } catch {
          case NonFatal(e) =>
            unhandledExceptionHandler(e)
        } finally {
          var success = false
          var handlersToRun: Option[List[() => Unit]] = None
          while (!success) {
            val state = poolState.get()
            if (state.submittedTasks > 1) {
              handlersToRun = None
              val newState = new PoolState(state.handlers, state.submittedTasks - 1)
              success = poolState.compareAndSet(state, newState)
            } else if (state.submittedTasks == 1) {
              handlersToRun = Some(state.handlers)
              val newState = new PoolState()
              success = poolState.compareAndSet(state, newState)
            } else {
              throw new Exception("BOOM")
            }
          }
          if (handlersToRun.nonEmpty) {
            handlersToRun.get.foreach { handler =>
              execute(new Runnable {
                def run(): Unit = handler()
              })
            }
          }
        }
      }
    })
  }

  private def registerForAwait[K <: Key[V], V](cell: Cell[K, V]) = {
    var success = false
    while (!success) {
      val registered = cellsAwaited.get()
      val newRegistered = registered + (cell -> cell)
      success = cellsAwaited.compareAndSet(registered, newRegistered)
    }
    runScheduledTasks(cell)
  }

  private def runScheduledTasks[K <: Key[V], V](cell: Cell[K, V]): Unit = {
    val oldScheduled = tasksScheduled.get()
    val scheduledForCell = oldScheduled.getOrElse(cell, Seq())
    val newScheduled = oldScheduled.filterKeys(_ ne cell)
    if (!tasksScheduled.compareAndSet(oldScheduled, newScheduled)) {
      runScheduledTasks(cell)
    } else {
      scheduledForCell.foreach(execute(_))
    }
  }

  def awaitResult[K <: Key[V], V](cell: Cell[K, V]): Future[V] = {
    val p = Promise[V]

    registerForAwait(cell)

    cell.onComplete(_ match {
      // If the result is already available, this will be executed immediately.
      case Success(v) => p.success(v)
      case _ => p.failure(null)
    })

    if (!cell.isComplete)
      execute(() => {
        val completer = cell.asInstanceOf[CellImpl[K, V]]
        val outcome = completer.init(completer.cell)
        outcome match {
          case FinalOutcome(v) =>
            completer.putFinal(v)
          case NextOutcome(v) =>
            completer.putNext(v)
            completer.cellDependencies.foreach(awaitResult(_))
          case NoOutcome =>
            completer.cellDependencies.foreach(awaitResult(_))
        }
      })
    p.future
  }

  def isAwaited[K <: Key[V], V](cell: Cell[K, V]) = cellsAwaited.get().contains(cell)

  def shutdown(): Unit =
    pool.shutdown()

  def reportFailure(t: Throwable): Unit =
    t.printStackTrace()
}
