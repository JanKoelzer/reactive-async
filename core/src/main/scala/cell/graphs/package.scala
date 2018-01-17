/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
/* Copied and slightly adapted from OPAL:
 * - All unused methods and imports have been removed.
 * - Use a List assigned to a var instead of mutable.Stack (deprecated since 2.12)
 */
package cell

import scala.collection.mutable

/**
 * This package defines graph algorithms as well as factory methods to describe and compute graphs
 * and trees.
 *
 * This package supports the following types of graphs:
 *  1.  graphs based on explicitly connected nodes ([[org.opalj.graphs.Node]]),
 *  1.  graphs where the relationship between the nodes are encoded externally
 *      ([[org.opalj.graphs.Graph]]).
 *
 * @author Michael Eichberg
 */
package object graphs {

  // ---------------------------------------------------------------------------------------
  //
  // Closed Strongly Connected Components
  //
  // ---------------------------------------------------------------------------------------

  private type DFSNum = Int // always a positive number >= 0
  private type CSCCId = Int // always a positive number >= 1

  private[this] val Undetermined: CSCCId = -1

  /**
   * Identifies closed strongly connected components in directed (multi-)graphs.
   *
   * @tparam N The type of the graph's nodes. The nodes have to correctly implements equals
   *         and hashCode.
   * @param  ns The nodes of the graph.
   * @param  es A function that, given a node, returns all successor nodes. Basically, the edges
   *         of the graph.
   */
  final def closedSCCs[N >: Null <: AnyRef](
    ns: Traversable[N],
    es: N ⇒ Traversable[N]): List[Iterable[N]] = {

    case class NInfo(val dfsNum: DFSNum, var cSCCId: CSCCId = Undetermined) {
      override def toString: String = {
        val cSCCId = this.cSCCId match {
          case Undetermined ⇒ "Undetermined"
          case id ⇒ id.toString
        }
        s"(dfsNum=$dfsNum,cSCCId=$cSCCId)"
      }
    }

    val nodeInfo: mutable.HashMap[N, NInfo] = mutable.HashMap.empty

    def setDFSNum(n: N, dfsNum: DFSNum): Unit = {
      assert(nodeInfo.get(n).isEmpty)
      nodeInfo.put(n, NInfo(dfsNum))
    }
    val hasDFSNum: (N) ⇒ Boolean = (n: N) ⇒ nodeInfo.get(n).isDefined
    val dfsNum: (N) ⇒ DFSNum = (n: N) ⇒ nodeInfo(n).dfsNum
    val setCSCCId: (N, CSCCId) ⇒ Unit = (n: N, cSCCId: CSCCId) ⇒ nodeInfo(n).cSCCId = cSCCId
    val cSCCId: (N) ⇒ CSCCId = (n: N) ⇒ nodeInfo(n).cSCCId

    closedSCCs(ns, es, setDFSNum, hasDFSNum, dfsNum, setCSCCId, cSCCId)
  }

  /**
   * A closed strongly connected component (cSCC) is a set of nodes of a graph where each node
   * belonging to the cSCC can be reached from another node and no node contains an edge to
   * another node that does not belong to the cSCC.
   *
   * @note    This implementation can handle (arbitrarily degenerated) graphs with up to
   *          Int.MaxValue nodes (if the VM is given enough memory!)
   *
   * Every such set is necessarily minimal/maximal.
   */
  def closedSCCs[N >: Null <: AnyRef](
    ns: Traversable[N],
    es: N ⇒ Traversable[N],
    setDFSNum: (N, DFSNum) ⇒ Unit,
    hasDFSNum: (N) ⇒ Boolean,
    dfsNum: (N) ⇒ DFSNum,
    setCSCCId: (N, CSCCId) ⇒ Unit,
    cSCCId: (N) ⇒ CSCCId): List[Iterable[N]] = {

    /* The following is not a strict requirement, more an expectation:
    assert(
        { val allNodes = ns.toSet; allNodes.forall { n ⇒ es(n).forall(allNodes.contains) } },
        "the graph references nodes which are not in the set of all nodes"
    )
    */

    // IMPROVE Instead of associating every node with its cSCCID it is also conceivable to just store the respective boundary nodes where a new cSCC candidate starts!

    // The algorithm used to compute the closed scc is loosely inspired by:
    // Information Processing Letters 74 (2000) 107–114
    // Path-based depth-first search for strong and biconnected components
    // Harold N. Gabow 1
    // Department of Computer Science, University of Colorado at Boulder
    //
    // However, we are interested in finding closed sccs; i.e., those strongly connected
    // components that have no outgoing dependencies.

    val PathElementSeparator: Null = null

    var cSCCs = List.empty[Iterable[N]]

    /*
     * Performs a depth-first search to locate an initial strongly connected component.
     * If we detect a connected component, we then check for every element belonging to
     * the connected component whether it also depends on an element which is not a member
     * of the strongly connected component. If Yes, we continue with the checking of the
     * other elements. If No, we perform a depth-first search based on the successor of the
     * node that does not belong to the SCC and try to determine if it is connected to some
     * previous SCC. If so, we merge all nodes as they belong to the same SCC.
     */
    def dfs(initialDFSNum: DFSNum, n: N): DFSNum = {
      if (hasDFSNum(n))
        return initialDFSNum;

      // CORE DATA STRUCTURES
      var thisPathFirstDFSNum = initialDFSNum
      var nextDFSNum = thisPathFirstDFSNum
      var nextCSCCId = 1
      val path = mutable.ArrayBuffer.empty[N]
      var worklist = List.empty[N]
      //val worklist = mutable.Stack.empty[N].asInstanceOf[mutable.Stack[N]] //Changed this for usage of `worklist` raised type errors.

      // HELPER METHODS
      def addToPath(n: N): DFSNum = {
        assert(!hasDFSNum(n))
        val dfsNum = nextDFSNum
        setDFSNum(n, dfsNum)
        path += n
        nextDFSNum += 1
        dfsNum
      }
      def pathLength = nextDFSNum - initialDFSNum // <=> path.length
      def killPath(): Unit = { path.clear(); thisPathFirstDFSNum = nextDFSNum }
      def reportPath(p: Iterable[N]): Unit = { cSCCs ::= p }

      // INITIALIZATION
      addToPath(n)
      worklist = es(n).toList ++ (PathElementSeparator :: n :: worklist)

      // PROCESSING
      while (worklist.nonEmpty) {
        // println(
        //  s"next iteration { path=${path.map(n ⇒ dfsNum(n)+":"+n).mkString(",")}; "+
        //  s"thisParthFirstDFSNum=$thisPathFirstDFSNum; "+
        //  s"nextDFSNum=$nextDFSNum; nextCSCCId=$nextCSCCId }")

        val n = worklist.head
        worklist = worklist.tail
        if (n eq PathElementSeparator) { // i.e., we have visited all child elements
          val n = worklist.head
          worklist = worklist.tail
          val nDFSNum = dfsNum(n)
          if (nDFSNum >= thisPathFirstDFSNum) {
            //                        println(s"visited all children of $n")
            val thisPathNDFSNum = nDFSNum - thisPathFirstDFSNum
            val nCSCCId = cSCCId(n)
            nCSCCId match {
              case Undetermined ⇒
                killPath()
              case nCSCCId if nCSCCId == cSCCId(path.last) &&
                (
                  thisPathNDFSNum == 0 /*all elements on the path define a cSCC*/ ||
                  nCSCCId != cSCCId(path(thisPathNDFSNum - 1))) ⇒
                reportPath(path.takeRight(pathLength - thisPathNDFSNum))
                killPath()

              case someCSCCId ⇒
                /*nothing to do*/
                assert(
                  // nDFSNum == 0 ???
                  nDFSNum == initialDFSNum || someCSCCId == cSCCId(path.last),
                  s"nDFSNum=$nDFSNum; nCSCCId=$nCSCCId; " +
                    s"cSCCId(path.last)=${cSCCId(path.last)}\n" +
                    s"(n=$n; initialDFSNum=$initialDFSNum; " +
                    s"thisPathFirstDFSNum=$thisPathFirstDFSNum\n" +
                    cSCCs.map(_.map(_.toString)).
                    mkString("found csccs:\n\t", "\n\t", "\n"))

            }
          } else {
            // println(s"visited all children of non-cSCC path element $n")
          }

        } else { // i.e., we are (potentially) extending our path
          // println(s"next node { $n; dfsNum=${if (hasDFSNum(n)) dfsNum(n) else N/A} }")

          if (hasDFSNum(n)) {
            // we have (at least) a cycle...
            val nDFSNum = dfsNum(n)
            if (nDFSNum >= thisPathFirstDFSNum) {
              // this cycle may become a cSCC
              val nCSCCId = cSCCId(n)
              nCSCCId match {
                case Undetermined ⇒
                  // we have a new cycle
                  val nCSCCId = nextCSCCId
                  nextCSCCId += 1
                  val thisPathNDFSNum = nDFSNum - thisPathFirstDFSNum
                  val cc = path.view.takeRight(pathLength - thisPathNDFSNum)
                  cc.foreach(n ⇒ setCSCCId(n, nCSCCId))
                // val header = s"Nodes in a cSCC candidate $nCSCCId: "
                // println(cc.mkString(header, ",", ""))
                // println("path: "+path.mkString)

                case nCSCCId ⇒
                  val thisPathNDFSNum = nDFSNum - thisPathFirstDFSNum
                  path.view.takeRight(pathLength - thisPathNDFSNum).foreach { n ⇒
                    setCSCCId(n, nCSCCId)
                  }
              }
            } else {
              // this cycle is related to a node that does not take part in a cSCC
              killPath()
            }
          } else {
            // we are visiting the element for the first time
            addToPath(n)
            worklist = PathElementSeparator :: n :: worklist
            es(n) foreach { nextN ⇒
              if (hasDFSNum(nextN) && dfsNum(nextN) < thisPathFirstDFSNum) {
                killPath()
              } else {
                worklist = nextN :: worklist
              }
            }
          }
        }
      }
      nextDFSNum
    }

    ns.foldLeft(0)((initialDFSNum, n) ⇒ dfs(initialDFSNum, n))

    cSCCs

  }

}
