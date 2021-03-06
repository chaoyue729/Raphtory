package com.raphtory

import akka.actor.{ActorSystem, Props}
import ch.qos.logback.classic.Level
import com.raphtory.api.Analyser
import com.raphtory.core.actors.AnalysisManager.{AnalysisManager, AnalysisRestApi}
import com.raphtory.core.actors.ClusterManagement.{RaphtoryReplicator, WatchDog, WatermarkManager}
import com.raphtory.core.actors.Router.GraphBuilder
import com.raphtory.core.actors.Spout.{Spout, SpoutAgent}
import com.raphtory.core.model.communication.{LiveAnalysisRequest, RangeAnalysisRequest, ViewAnalysisRequest}
import kamon.Kamon
import org.slf4j.LoggerFactory

object RaphtoryGraph {
  def apply[T](spout: Spout[T], graphBuilder: GraphBuilder[T]) : RaphtoryGraph[T] =
    new RaphtoryGraph(spout, graphBuilder)

  def apply[T](spoutPath: String, graphBuilderPath: String) : RaphtoryGraph[T] ={
    val spout = Class.forName(spoutPath).getConstructor().newInstance().asInstanceOf[Spout[T]]
    val graphBuilder = Class.forName(graphBuilderPath).getConstructor().newInstance().asInstanceOf[GraphBuilder[T]]
    new RaphtoryGraph(spout, graphBuilder)
  }

}

class RaphtoryGraph[T](spout: Spout[T], graphBuilder: GraphBuilder[T]) {
  Kamon.init() //start tool logging

//  val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
  //root.setLevel(Level.ERROR)
  val system = ActorSystem("Citation-system")

  val partitionNumber = 1
  val minimumRouters  = 1
  system.actorOf(Props(new WatermarkManager(partitionNumber)),"WatermarkManager")
  system.actorOf(Props(new WatchDog(partitionNumber, minimumRouters)), "WatchDog")

  system.actorOf(Props(new SpoutAgent(spout)), "Spout")
  system.actorOf(Props(RaphtoryReplicator.apply("Router", partitionNumber, minimumRouters,graphBuilder)), s"Routers")
  system.actorOf(Props(RaphtoryReplicator("Partition Manager", partitionNumber,minimumRouters)), s"PartitionManager")

  val analysisManager = system.actorOf(Props[AnalysisManager], s"AnalysisManager")
  AnalysisRestApi(system)

  //TODO tidy these, but will be done with full analysis Overhall
  def rangeQuery(analyser:Analyser,start:Long,end:Long,increment:Long,args:Array[String]):Unit = {
    analysisManager ! RangeAnalysisRequest(analyser.getClass.getCanonicalName,start,end,increment,"false",0L,Array[Long](),args,"")
  }

  def rangeQuery(analyser:Analyser,start:Long,end:Long,increment:Long,window:Long,args:Array[String]):Unit = {
    analysisManager ! RangeAnalysisRequest(analyser.getClass.getCanonicalName,start,end,increment,"true",window,Array[Long](),args,"")
  }
  def rangeQuery(analyser:Analyser,start:Long,end:Long,increment:Long,windowBatch:Array[Long],args:Array[String]):Unit = {
    analysisManager ! RangeAnalysisRequest(analyser.getClass.getCanonicalName,start,end,increment,"batched",0L,windowBatch,args,"")
  }

  def viewQuery(analyser:Analyser,timestamp:Long,args:Array[String]):Unit = {
    analysisManager ! ViewAnalysisRequest(analyser.getClass.getCanonicalName,timestamp,"false",0L,Array[Long](),args,"")
  }

  def viewQuery(analyser:Analyser,timestamp:Long,window:Long,args:Array[String]):Unit = {
    analysisManager ! ViewAnalysisRequest(analyser.getClass.getCanonicalName,timestamp,"true",window,Array[Long](),args,"")
  }
  def viewQuery(analyser:Analyser,timestamp:Long,windowBatch:Array[Long],args:Array[String]):Unit = {
    analysisManager ! ViewAnalysisRequest(analyser.getClass.getCanonicalName,timestamp,"batched",0L,windowBatch,args,"")
  }

  def liveQuery(analyser:Analyser,repeat:Long,eventTime:Boolean,args:Array[String]):Unit = {
    analysisManager ! LiveAnalysisRequest(analyser.getClass.getCanonicalName,repeat,eventTime,"false",0L,Array[Long](),args,"")
  }

  def liveQuery(analyser:Analyser,repeat:Long,eventTime:Boolean,window:Long,args:Array[String]):Unit = {
    analysisManager ! LiveAnalysisRequest(analyser.getClass.getCanonicalName,repeat,eventTime,"true",window,Array[Long](),args,"")
  }

  def liveQuery(analyser:Analyser,repeat:Long,eventTime:Boolean,windowBatch:Array[Long],args:Array[String]):Unit = {
    analysisManager ! LiveAnalysisRequest(analyser.getClass.getCanonicalName,repeat,eventTime,"batched",0L,windowBatch,args,"")
  }

}
