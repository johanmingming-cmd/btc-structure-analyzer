package com.btcstructureanalyzer
import org.junit.Test
import java.io.File

/** Multi-coin historical research; every selected pair must have complete closed-candle data. */
class HistoricalMultiCoinValidationTest {
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
  val manifest=File(root,"manifest.json")
  require(manifest.isFile) { "Fresh dataset manifest missing: ${manifest.absolutePath}" }
  val manifestText=manifest.readText()
  require(manifestText.contains("\\\"schemaVersion\\\": 1") && manifestText.contains("Binance Spot public klines")) { "Unsupported or invalid dataset manifest" }
  val symbolsBlock=Regex("\\\"symbols\\\"\\s*:\\s*\\[([^]]*)]").find(manifestText)?.groupValues?.get(1)
      ?: error("Manifest symbols list missing")
  val symbols=Regex("\\\"([A-Z0-9]+USDT)\\\"").findAll(symbolsBlock).map{it.groupValues[1]}.toList()
  require(symbols.size>=10 && symbols.distinct().size==symbols.size) { "Manifest must list at least 10 unique symbols" }
  val actualDirs=root.listFiles()?.filter{it.isDirectory}?.map{it.name}?.toSet().orEmpty()
  require(actualDirs==symbols.toSet()) { "Dataset folders do not exactly match fresh manifest symbols" }
  val coins=symbols.map{File(root,it)}
  val aggregates=MultiCoinMetricsAggregator()
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
    aggregates.add(row)
   }
  }
  for(row in aggregates.results()){
   println("MULTI_COIN_AGGREGATE horizonMinutes=${row.horizonBars*5} coins=${coins.size} candidates=${row.candidateCount} signals=${row.evaluated} hits=${row.directionalHits} misses=${row.misses} neutral=${row.neutral} waits=${row.waitCount} coveragePct=${row.coveragePercent} hitRatePct=${row.hitRatePercent} meanSignedReturnPct=${row.meanSignedReturnPercent} maxAbsReturnPct=${row.maxAbsoluteReturnPercent}")
  }
  require(aggregates.horizons()==setOf(12,36,72)) { "Expected aggregate metrics for 1h, 3h and 6h horizons" }
 }
}
