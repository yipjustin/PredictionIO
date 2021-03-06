package io.prediction.examples.stock_old

import io.prediction.controller.Params
import io.prediction.controller.Metrics
import com.github.nscala_time.time.Imports._
import scala.collection.mutable.{ Map => MMap, ArrayBuffer }

case class BacktestingParams(
  val enterThreshold: Double,
  val exitThreshold: Double,
  val maxPositions: Int = 1)
extends Params {}

// prediction is Ticker -> ({1:Enter, -1:Exit}, ActualReturn)
class DailyResult(
  val date: DateTime,
  val actualReturn: Map[String, Double],  // Tomorrow's return
  val toEnter: Seq[String],
  val toExit: Seq[String])
extends Serializable {}

class BacktestingMetrics(val params: BacktestingParams)
  extends Metrics[BacktestingParams, AnyRef, Query, Target, Target,
      DailyResult, Seq[DailyResult], String] {

  def computeUnit(query: Query, predicted: Target, actual: Target)
    : DailyResult = {
    val predictedData = predicted.data
    val actualData = actual.data

    // Decide enter / exit, also sort by pValue desc
    val data = predictedData
    .map { case (ticker, pValue) => {
      val dir = pValue match {
        case p if p >= params.enterThreshold => 1
        case p if p <= params.exitThreshold => -1
        case _ => 0
      }
      (ticker, dir, pValue, actualData(ticker))
    }}
    .toArray
    .sortBy(-_._3)

    val toEnter = data.filter(_._2 == 1).map(_._1)
    val toExit = data.filter(_._2 == -1).map(_._1)
    val actualReturn = data.map(e => (e._1, e._4)).toMap
    
    new DailyResult(
      date = query.today, 
      actualReturn = actualReturn,
      toEnter = toEnter,
      toExit = toExit)
  }
 
  def computeSet(dp: AnyRef, input: Seq[DailyResult])
    : Seq[DailyResult] = input

  def computeMultipleSets(input: Seq[(AnyRef, Seq[DailyResult])])
  : String = {
    var dailyResultsSeq = input
      .map(_._2)
      .flatten
      .toArray
      .sortBy(_.date)

    val dailyNavs = ArrayBuffer[Double]()
    val ss = ArrayBuffer[String]()

    var cash = 1000000.0
    val positions = MMap[String, Double]()
    val maxPositions = params.maxPositions

    for (daily <- dailyResultsSeq) {
      val today = daily.date
      // Determine exit
      daily.toExit.foreach { ticker => {
        if (positions.contains(ticker)) {
          val money = positions.remove(ticker).get
          cash += money
          val s = s"Exit . D: $today T: $ticker M: $money"
          ss.append(s)
        }
      }}

      // Determine enter
      val slack = maxPositions - positions.size
      val money = cash / slack
      daily.toEnter
      .filter(t => !positions.contains(t))
      .take(slack)
      .map{ ticker => {
        cash -= money
        positions += (ticker -> money)
        val s = s"Enter. D: $today T: $ticker M: $money"
        ss.append(s)
      }}

      // Update price change
      positions.keys.foreach { ticker => {
        positions(ticker) *= (1 + daily.actualReturn(ticker))
      }}

      // Book keeping
      val nav = cash + positions.values.sum     
      val s = s"$today Nav: $nav Pos: ${positions.size}"
      ss.append(s)
    }
    ss.mkString("\n")
  }
}
