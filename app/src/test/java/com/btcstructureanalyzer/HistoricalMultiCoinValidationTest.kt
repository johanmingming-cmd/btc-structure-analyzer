package com.btcstructureanalyzer
import org.junit.Test
import java.io.File

/** Multi-coin historical research; every selected pair must have complete closed-candle data. */
class HistoricalMultiCoinValidationTest {
 private data class Aggregate(
  var candidates:Int=0,
  var signals:Int=0,
  var hits:Int=0,
  var misses:Int=0,
  var neutral:Int=0,
  var waits:Int=0,
  var signedReturnSum:Double=0.0,
  var maxAbsReturn:Double=0.0
 )
 private fun requireValidSeries(candles:List<Candle>,interval:Long,label:String){
  require(candles.zipWithNext().all{(a,b)->b.openTime-a.openTime==interval}) { "Candle gap in $label" }
  require(candles.all{c->
   c.openTime>=0L && c.closeTime==c.openTime+interval-1L &&
    c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
    c.volume.isFinite() && c.open>0.0 && c.high>=maxOf(c.open,c.close) &&
    c.low<=minOf(c.open,c.close) && c.low>0.0 && c.volume>=0.0
  }) { "Invalid OHLCV or close timestamp in $label" }
 }
 private fun load(file:File):List<Candle>{
  require(file.isFile) { "Required historical dataset is missing: ${file.absolutePath}" }
  return file.readLines().drop(1).filter{it.isNotBlank()}.map{line->
   val p=line.split(",")
   require(p.size>=7) { "Malformed CSV row: $line" }
   Candle(p[0].toLong(),p[1].toDouble(),p[2].toDouble(),p[3].toDouble(),p[4].toDouble(),p[5].toDouble(),p[6].toLong())
  }
 }
 @Test fun evaluateTopLiquidUsdtSpotCoinsAcrossFlexibleHorizons(){
  val root=File(System.getenv("MULTI_HISTORY_DIR")?:"build/multi-coin-history")
  require(root.isDirectory) { "Multi-coin history directory missing: ${root.absolutePath}" }
  val coins=root.listFiles()?.filter{it.isDirectory}?.sortedBy{it.name}.orEmpty()
  require(coins.size>=10) { "Expected at least 10 coin datasets, found ${coins.size}" }
  val aggregates=mutableMapOf<Int,Aggregate>()
  for(coin in coins){
   val h1=load(File(coin,"1h.csv"));val m15=load(File(coin,"15m.csv"));val m5=load(File(coin,"5m.csv"))
   require(h1.size>1000 && m15.size>4000 && m5.size>12000) { "Insufficient history for ${coin.name}: ${h1.size}/${m15.size}/${m5.size}" }
   requireValidSeries(h1,3600000L,"${coin.name} 1h")
   requireValidSeries(m15,900000L,"${coin.name} 15m")
   requireValidSeries(m5,300000L,"${coin.name} 5m")
   val result=WalkForwardEvaluator.evaluate(m5,listOf(12,36,72),0.60,evaluationStep=12){history->
    val decisionTime=history.last().closeTime+1L
    AdaptivePredictionEngine.evaluate(AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history)).decision
   }
   println("MULTI_COIN_DATASET symbol=${coin.name} bars1h=${h1.size} bars15m=${m15.size} bars5m=${m5.size}")
   result.horizons.forEach{row->
    println("MULTI_COIN_METRIC symbol=${coin.name} horizonMinutes=${row.horizonBars*5} candidates=${row.candidateCount} signals=${row.evaluated} waits=${row.waitCount} coveragePct=${row.coveragePercent} hitRatePct=${row.hitRatePercent} meanSignedReturnPct=${row.meanSignedReturnPercent} maxAbsReturnPct=${row.maxAbsoluteReturnPercent}")
    val aggregate=aggregates.getOrPut(row.horizonBars){Aggregate()}
    aggregate.candidates+=row.candidateCount
    aggregate.signals+=row.evaluated
    aggregate.hits+=row.directionalHits
    aggregate.misses+=row.misses
    aggregate.neutral+=row.neutral
    aggregate.waits+=row.waitCount
    aggregate.signedReturnSum+=(row.meanSignedReturnPercent?:0.0)*row.evaluated
    aggregate.maxAbsReturn=maxOf(aggregate.maxAbsReturn,row.maxAbsoluteReturnPercent?:0.0)
   }
  }
  for((h,a) in aggregates.toSortedMap()){
   val coverage=if(a.candidates==0)0.0 else a.signals*100.0/a.candidates
   val hitRate=if(a.signals==0)0.0 else a.hits*100.0/a.signals
   val meanReturn=if(a.signals==0)null else a.signedReturnSum/a.signals
   println("MULTI_COIN_AGGREGATE horizonMinutes=${h*5} coins=${coins.size} candidates=${a.candidates} signals=${a.signals} hits=${a.hits} misses=${a.misses} neutral=${a.neutral} waits=${a.waits} coveragePct=$coverage hitRatePct=$hitRate meanSignedReturnPct=$meanReturn maxAbsReturnPct=${a.maxAbsReturn}")
  }
  require(aggregates.keys==setOf(12,36,72)) { "Expected aggregate metrics for 1h, 3h and 6h horizons" }
 }
}
