package com.btcstructureanalyzer
import org.junit.Test
import java.io.File

/** Real-data research run; missing downloaded history is a hard failure, never a silent skip. */
class HistoricalBtcValidationTest {
 private fun load(interval:String):List<Candle>{
  val root=System.getenv("BTC_HISTORY_DIR")?:"build/btc-history"
  val f=File(root,"BTCUSDT_$interval.csv")
  require(f.isFile) { "Required historical dataset is missing: ${f.absolutePath}" }
  return f.readLines().drop(1).filter{it.isNotBlank()}.map{line->
   val p=line.split(",")
   require(p.size>=7) { "Malformed $interval CSV row: $line" }
   Candle(p[0].toLong(),p[1].toDouble(),p[2].toDouble(),p[3].toDouble(),p[4].toDouble(),p[5].toDouble(),p[6].toLong())
  }
 }
 private fun validateSeries(candles:List<Candle>,interval:Long,label:String){
  require(candles.zipWithNext().all{(a,b)->b.openTime-a.openTime==interval}) { "$label candle gap detected" }
  require(candles.all{c->
   c.openTime>=0L && c.closeTime==c.openTime+interval-1L &&
    c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
    c.volume.isFinite() && c.open>0.0 && c.high>=maxOf(c.open,c.close) &&
    c.low<=minOf(c.open,c.close) && c.low>0.0 && c.volume>=0.0
  }) { "Invalid OHLCV or close timestamp in $label" }
 }
 @Test fun evaluateRealBtcSpotHistoryAcrossFlexibleHorizons(){
  val h1=load("1h");val m15=load("15m");val m5=load("5m")
  require(h1.size>1000 && m15.size>4000 && m5.size>12000) { "Historical dataset is unexpectedly short: 1h=${h1.size}, 15m=${m15.size}, 5m=${m5.size}" }
  validateSeries(h1,3600000L,"1h")
  validateSeries(m15,900000L,"15m")
  validateSeries(m5,300000L,"5m")
  val diagnostics=mutableMapOf<String,Int>()
  val result=WalkForwardEvaluator.evaluate(m5,listOf(12,36,72),0.60,evaluationStep=12){history->
   val decisionTime=history.last().closeTime+1L
   val engine=AdaptivePredictionEngine.evaluate(AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history))
   val key="${engine.regime}|${engine.reason}|${engine.explanation}"
   diagnostics[key]=(diagnostics[key]?:0)+1
   engine.decision
  }
  println("BTCUSDT Spot historical walk-forward: bars1h=${h1.size}, bars15m=${m15.size}, bars5m=${m5.size}, splitIndex=${result.trainEndExclusive}, testEndIndex=${result.testEndExclusive}, evaluationStep=12")
  diagnostics.toSortedMap().forEach{(reason,count)->println("BTC_HISTORICAL_DIAGNOSTIC count=$count state=$reason")}
  result.horizons.forEach{row->
   println("HISTORICAL_METRIC horizonMinutes=${row.horizonBars*5}, candidates=${row.candidateCount}, signals=${row.evaluated}, waits=${row.waitCount}, coveragePct=${row.coveragePercent}, hitRatePct=${row.hitRatePercent}, meanSignedReturnPct=${row.meanSignedReturnPercent}, maxAbsReturnPct=${row.maxAbsoluteReturnPercent}")
  }
  require(result.horizons.all{it.candidateCount>0}) { "No historical decisions were evaluated" }
 }
}
