package com.raphtory.algorithms

import com.raphtory.api.Analyser
import com.raphtory.core.model.analysis.entityVisitors.VertexVisitor

import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.immutable
import scala.collection.parallel.mutable.ParArray
import scala.reflect.io.Path

object LPA {
  def apply(args:Array[String]): LPA = new LPA(args)
}

class LPA(args: Array[String]) extends Analyser(args) {
  //args = [top output, edge property, max iterations]
  val arg: Array[String] = args.map(_.trim)
  val top_c: Int         = if (arg.length == 0) 0 else arg.head.toInt
  val PROP: String       = if (arg.length < 2) "" else arg(1)
  val maxIter: Int       = if (arg.length < 3) 500 else arg(2).toInt

  val output_file: String = System.getenv().getOrDefault("LPA_OUTPUT_PATH", "/app/out.json").trim
  val nodeType: String    = System.getenv().getOrDefault("NODE_TYPE", "").trim

  override def setup(): Unit =
    view.getVertices().foreach { vertex =>
      val lab = scala.util.Random.nextLong()
      vertex.setState("lpalabel", lab)
      vertex.messageAllNeighbours((vertex.ID(), lab))
    }

  override def analyse(): Unit =
    view.getMessagedVertices().foreach { vertex =>
      try {
        val Vlabel = vertex.getState[Long]("lpalabel")

        // Get neighbourhood Frequencies -- relevant to weighted LPA
        val vneigh = vertex.getOutEdges ++ vertex.getIncEdges
        val neigh_freq = vneigh
          .map(e => (e.ID(), e.getPropertyValue(PROP).getOrElse(1L).asInstanceOf[Long]))
          .groupBy(_._1)
          .mapValues(x => x.map(_._2).sum)
        val vfreq = if (vneigh.nonEmpty) neigh_freq.values.sum / vneigh.map(_.ID()).toSet.size else 1L

        // Process neighbour labels into (label, frequency)
        val gp = vertex.messageQueue[(Long, Long)].map(v => (v._2, neigh_freq(v._1)))
        gp.append((Vlabel, vfreq))

        // Update node label and broadcast
        val newLabel = gp.groupBy(_._1).mapValues(_.map(_._2).sum).maxBy(_._2)._1
        newLabel match {
          case Vlabel => vertex.voteToHalt()
          case _      => vertex.setState("lpalabel", newLabel)
        }

        vertex.messageAllNeighbours((vertex.ID(), newLabel))
        doSomething(vertex, gp.dropRight(1).map(_._1).toArray)
      } catch {
        case e: Exception => println(e, vertex.ID())
      }
    }

  def doSomething(v: VertexVisitor, gp: Array[Long]): Unit = {}

  override def returnResults(): Any =
    view
      .getVertices()
      .filter(v => v.Type() == nodeType)
      .map(vertex => (vertex.getState[Long]("lpalabel"), vertex.ID()))
      .groupBy(f => f._1)
      .map(f => (f._1, f._2.map(_._2)))

  override def processResults(results: ArrayBuffer[Any], timestamp: Long, viewCompleteTime: Long): Unit = {
    val er      = extractData(results)
    val commtxt = er.communities.map(x => s"""[${x.mkString(",")}]""")
    val text = s"""{"time":$timestamp,"top5":[${er.top5
      .mkString(",")}],"total":${er.total},"totalIslands":${er.totalIslands},"communities": [${commtxt
      .mkString(",")}], "viewTime":$viewCompleteTime}"""
//    Path(output_file).createFile().appendAll(text + "\n")
    println(text)
  }

  override def processWindowResults(
      results: ArrayBuffer[Any],
      timestamp: Long,
      windowSize: Long,
      viewCompleteTime: Long
  ): Unit = {
    val er      = extractData(results)
    val commtxt = er.communities.map(x => s"""[${x.mkString(",")}]""")
    val text = s"""{"time":$timestamp,"windowsize":$windowSize,"top5":[${er.top5
      .mkString(",")}],"total":${er.total},"totalIslands":${er.totalIslands},"communities": [${commtxt
      .mkString(",")}], "viewTime":$viewCompleteTime}"""
//    Path(output_file).createFile().appendAll(text + "\n")
    println(text)
  }

  def extractData(results: ArrayBuffer[Any]): fd = {
    val endResults = results.asInstanceOf[ArrayBuffer[immutable.ParHashMap[Long, ParArray[String]]]]
    try {
      val grouped             = endResults.flatten.groupBy(f => f._1).mapValues(x => x.flatMap(_._2))
      val groupedNonIslands   = grouped.filter(x => x._2.size > 1)
      val sorted              = grouped.toArray.sortBy(_._2.size)(sortOrdering)
      val top5                = sorted.map(_._2.size).take(5)
      val total               = grouped.size
      val totalWithoutIslands = groupedNonIslands.size
      val totalIslands        = total - totalWithoutIslands
      val communities         = if (top_c == 0) sorted.map(_._2) else sorted.map(_._2).take(top_c)
      fd(top5, total, totalIslands, communities)
    } catch {
      case e: UnsupportedOperationException => fd(Array(0), 0, 0, Array(ArrayBuffer("0")))
    }
  }

  override def defineMaxSteps(): Int = maxIter

}

case class fd(top5: Array[Int], total: Int, totalIslands: Int, communities: Array[ArrayBuffer[String]])
