package com.btcstructureanalyzer
import org.junit.Test
import java.io.File

/** Multi-coin historical research; every selected pair must have complete closed-candle data. */
class HistoricalMultiCoinValidationTest {
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
  val totals=mutableMapOf<Int,MutableList<Double>>()
  val counts=mutableMapOf<Int,IntArray>()
  for(coin in coins){
   val h1=load(File(coin,"1h.csv"));val m15=load(File(coin,"15m.csv"));val m5=load(File(coin,"5m.csv"))
   require(h1.size>1000 && m15.size>4000 && m5.size>12000) { "Insufficient history for ${coin.name}: ${h1.size}/${m15.size}/${m5.size}" }
   require(h1.zipWithNext().all{(a,b)->b.openTime-a.openTime==3600000L}) { "1h gap in ${coin.name}" }
   require(m15.zipWithNext().all{(a,b)->b.openTime-a.openTime==900000L}) { "15m gap in ${coin.name}" }
   require(m5.zipWithNext().all{(a,b)->b.openTime-a.openTime==300000L}) { "5m gap in ${coin.name}" }
   val result=WalkForwardEvaluator.evaluate(m5,listOf(12,36,72),0.60,evaluationStep=12){history->
    val decisionTime=history.last().closeTime+1L
    AdaptivePredictionEngine.evaluate(AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history)).decision
   }
   println("MULTI_COIN_DATASET symbol=${coin.name} bars1h=${h1.size} bars15m=${m15.size} bars5m=${m5.size}")
   result.horizons.forEach{row->
    println("MULTI_COIN_METRIC symbol=${coin.name} horizonMinutes=${row.horizonBars*5} candidates=${row.candidateCount} signals=${row.evaluated} waits=${row.waitCount} coveragePct=${row.coveragePercent} hitRatePct=${row.hitRatePercent} meanSignedReturnPct=${row.meanSignedReturnPercent} maxAbsReturnPct=${row.maxAbsoluteReturnPercent}")
    val v=totals.getOrPut(row.horizonBars){mutableListOf()};v.addAll(listOf(row.evaluated.toDouble(),row.directionalHits.toDouble(),row.waitCount.toDouble(),row.candidateCount.toDouble()))
   }
  }
  for((h,v) in totals.toSortedMap()){
   val evaluated=v[0].toInt();val hits=v[1].toInt();val waits=v[2].toInt();val candidates=v[3].toInt()
   println("MULTI_COIN_AGGREGATE horizonMinutes=${h*5} coins=${coins.size} signals=$evaluated directionalHits=$hits waits=$waits candidates=$candidates coveragePct=${if(candidates==0)0.0 else evaluated*100.0/candidates} hitRatePct=${if(evaluated==0)0.0 else hits*100.0/evaluated}")
  }
  require(totals.size==3) { "Expected aggregate metrics for 1h, 3h and 6h horizons" }
 }
}
