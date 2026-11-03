package com.btcstructureanalyzer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real-data research run, enabled after downloading public Binance klines in CI. */
class HistoricalBtcValidationTest {
 private fun load(interval:String):List<Candle>{
  val root=System.getenv("BTC_HISTORY_DIR")?:"build/btc-history";val f=File(root,"BTCUSDT_$interval.csv")
  return f.readLines().drop(1).filter{it.isNotBlank()}.map{line->val p=line.split(",");Candle(p[0].toLong(),p[1].toDouble(),p[2].toDouble(),p[3].toDouble(),p[4].toDouble(),p[5].toDouble(),p[6].toLong())}
 }
 @Test fun evaluateRealBtcSpotHistoryAcrossFlexibleHorizons(){
  val root=System.getenv("BTC_HISTORY_DIR")?:"build/btc-history";assumeTrue("Historical BTC dataset is downloaded in CI",File(root,"BTCUSDT_5m.csv").isFile)
  val h1=load("1h");val m15=load("15m");val m5=load("5m")
  require(h1.zipWithNext().all{(a,b)->b.openTime-a.openTime==3600000L});require(m15.zipWithNext().all{(a,b)->b.openTime-a.openTime==900000L});require(m5.zipWithNext().all{(a,b)->b.openTime-a.openTime==300000L})
  val result=WalkForwardEvaluator.evaluate(m5,listOf(12,36,72),0.60){history->
   val decisionTime=history.last().closeTime+1L
   AdaptivePredictionEngine.evaluate(AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history)).decision
  }
  println("BTCUSDT Spot historical walk-forward: bars5m=${m5.size}, trainEnd=${result.trainEndExclusive}, testEnd=${result.testEndExclusive}")
  result.horizons.forEach{row->println("horizonBars=${row.horizonBars}, candidates=${row.candidateCount}, signals=${row.evaluated}, waits=${row.waitCount}, coveragePct=${row.coveragePercent}, hitRatePct=${row.hitRatePercent}, meanSignedReturnPct=${row.meanSignedReturnPercent}, maxAbsReturnPct=${row.maxAbsoluteReturnPercent}")}
  require(result.horizons.all{it.candidateCount>0})
 }
}
