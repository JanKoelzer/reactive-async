package cell

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import lattice.{DefaultKey, Key, Lattice}
import org.opalj.graphs._

/* Need to have reference equality for CAS.
 */
class PoolState(val handlers: List[() => Unit] = List(), val submittedTasks: Int = 0) {
  def isQuiescent(): Boolean =
    submittedTasks == 0
}

class HandlerPool(parallelism: Int = 8, unhandledExceptionHandler: Throwable => Unit = _.printStackTrace()) {

  private val pool: ForkJoinPool = new ForkJoinPool(parallelism)

  private val poolState = new AtomicReference[PoolState](new PoolState)

  // Cells that have not been completed
  private val cellsNotDone = new AtomicReference[Map[Cell[_, _], Cell[_, _]]](Map())

  /** Returns a new cell in this HandlerPool.
    *
    * Creates a new cell with the given key. The `init` method is used to
    * retrieve an initial value for that cell and to set up dependencies via `whenNext`.
    * It gets called, when the cell is awaited, either directly by the awaitResult method
    * of the HandlerPool or if a cell that depends on this cell is awaited.
    *
    * @param key The key to resolve this cell if in a cycle or insufficient input.
    * @param init A callback to return the initial value for this cell and to set up dependencies.
    * @param lattice The lattice of which the values of this cell are taken from.
    * @return Returns a cell.
    */
  def createCell[K <: Key[V], V](key: K, init: () => Outcome[V])(implicit lattice: Lattice[V]): Cell[K, V] = {
    CellCompleter(this, key, init)(lattice).cell
  }

  /** Returns a new cell in this HandlerPool.
    *
    * Creates a new, completed cell with value `v`.
    *
    * @param lattice The lattice of which the values of this cell are taken from.
    * @return Returns a cell with value `v`.
    */
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


  /** Register a cell at this HandlerPool.
    *
    * @param cell The cell.
    */
  private[cell] def register[K <: Key[V], V](cell: Cell[K, V]): Unit = {
    val registered = cellsNotDone.get()
    val newRegistered = registered + (cell -> cell)
    cellsNotDone.compareAndSet(registered, newRegistered)
  }

  /** Deregister a cell at this HandlerPool.
    *
    * @param cell The cell.
    */
  private[cell] def deregister[K <: Key[V], V](cell: Cell[K, V]): Unit = {
    var success = false
    while (!success) {
      val registered = cellsNotDone.get()
      val newRegistered = registered - cell
      success = cellsNotDone.compareAndSet(registered, newRegistered)
    }
  }

  /** Returns all non-completed cells, when quiescence is reached. */
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
        val cSCCs = closedSCCs(registered, (cell: Cell[K, V]) => cell.totalCellDependencies)
        cSCCs.foreach(cSCC => resolveCycle(cSCC.asInstanceOf[Seq[Cell[K, V]]]))
      }
      p.success(true)
    }
    p.future
  }

  def quiescentResolveDefaults[K <: Key[V], V]: Future[Boolean] = {
    val p = Promise[Boolean]
    this.onQuiescent { () =>
      // Finds the rest of the unresolved cells (that have been triggered)
      val rest = this.cellsNotDone.get().values.filter(_.isRunning).asInstanceOf[Iterable[Cell[K, V]]].toSeq
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
        val cSCCs = closedSCCs(registered, (cell: Cell[K, V]) => cell.totalCellDependencies)
        cSCCs.foreach(cSCC => resolveCycle(cSCC.asInstanceOf[Seq[Cell[K, V]]]))
      }
      // Finds the rest of the unresolved cells (that have been triggered)
      val rest = this.cellsNotDone.get().values.filter(_.isRunning).asInstanceOf[Iterable[Cell[K, V]]].toSeq
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

  private[cell] def execute(fun: () => Unit): Unit =
    execute(new Runnable { def run(): Unit = fun() })

  private[cell] def execute(task: Runnable): Unit = {
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


  /** If a cell is triggered, it's `init` method is
    * run to both get an initial (or possibly final) value
    * and to set up dependencies. All dependees automatically
    * get triggered.
    *
    * @param cell The cell that is triggered.
    */
  def triggerExecution[K <: Key[V], V](cell: Cell[K, V]): Unit = {
    if (cell.markAsRunning())
      execute(() => {
        val completer = cell.asInstanceOf[CellImpl[K, V]]
        val outcome = completer.init()
        outcome match {
          case FinalOutcome(v) =>
            completer.putFinal(v)
          case NextOutcome(v) =>
            completer.putNext(v)
            completer.cellDependencies.foreach(triggerExecution)
          case NoOutcome =>
            completer.cellDependencies.foreach(triggerExecution)
        }
      })
  }

  /** Possibly initiates an orderly shutdown in which previously
    * submitted tasks are executed, but no new tasks will be accepted.
    */
  def shutdown(): Unit =
    pool.shutdown()

  def reportFailure(t: Throwable): Unit =
    t.printStackTrace()
}
