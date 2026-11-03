package com.btcstructureanalyzer
import org.junit.Test
import org.json.JSONObject
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
  val manifestJson=JSONObject(manifest.readText())
  require(manifestJson.optInt("schemaVersion",-1)==1) { "Unsupported dataset manifest schema" }
  require(manifestJson.optString("source")=="Binance Spot public klines") { "Unexpected historical data source" }
  require(manifestJson.optInt("requestedCoins",0)>=10) { "Manifest must request at least 10 coins" }
  val requestedDays=manifestJson.optInt("requestedDays",0)
  require(requestedDays>=180) { "This validation requires at least 180 requested history days; found $requestedDays" }
  val requestedStart=manifestJson.optLong("startTimeMs",0L)
  val requestedEnd=manifestJson.optLong("endTimeMs",0L)
  require(requestedStart>0L && requestedEnd>requestedStart) { "Invalid requested history window in manifest" }
  val minimumSpan=(requestedDays*86400000.0*0.95).toLong()
  val minimumBars=manifestJson.getJSONObject("minimumBars")
  val intervals=manifestJson.getJSONArray("intervals").let{array->(0 until array.length()).map{array.getString(it)}.toSet()}
  require(intervals==setOf("1h","15m","5m")) { "Manifest intervals must be exactly 1h, 15m and 5m" }
  val symbolsArray=manifestJson.getJSONArray("symbols")
  val symbols=(0 until symbolsArray.length()).map{symbolsArray.getString(it)}
  require(symbols.all{Regex("[A-Z0-9]+USDT").matches(it)}) { "Manifest contains an invalid USDT symbol" }
  require(symbols.size>=10 && symbols.distinct().size==symbols.size) { "Manifest must list at least 10 unique symbols" }
  val actualDirs=root.listFiles()?.filter{it.isDirectory}?.map{it.name}?.toSet().orEmpty()
  require(actualDirs==symbols.toSet()) { "Dataset folders do not exactly match fresh manifest symbols" }
  val coins=symbols.map{File(root,it)}
  val aggregates=MultiCoinMetricsAggregator()
  for(coin in coins){
   val h1=load(File(coin,"1h.csv"));val m15=load(File(coin,"15m.csv"));val m5=load(File(coin,"5m.csv"))
   require(h1.size>=minimumBars.getInt("1h") && m15.size>=minimumBars.getInt("15m") && m5.size>=minimumBars.getInt("5m")) { "Insufficient history for ${coin.name}: ${h1.size}/${m15.size}/${m5.size}" }
   require(h1.first().openTime<=requestedStart+3600000L && h1.last().closeTime>=requestedEnd-minimumSpan) { "1h history does not span at least 95% of requested window for ${coin.name}" }
   require(m15.first().openTime<=requestedStart+900000L && m15.last().closeTime>=requestedEnd-minimumSpan) { "15m history does not span at least 95% of requested window for ${coin.name}" }
   require(m5.first().openTime<=requestedStart+300000L && m5.last().closeTime>=requestedEnd-minimumSpan) { "5m history does not span at least 95% of requested window for ${coin.name}" }
   requireValidSeries(h1,3600000L,"${coin.name} 1h")
   requireValidSeries(m15,900000L,"${coin.name} 15m")
   requireValidSeries(m5,300000L,"${coin.name} 5m")
   val diagnostics=mutableMapOf<String,Int>()
   val result=WalkForwardEvaluator.evaluate(m5,listOf(12,36,72),0.60,evaluationStep=12){history->
    val decisionTime=history.last().closeTime+1L
    val engine=AdaptivePredictionEngine.evaluate(AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history))
    val key="${engine.regime}|${engine.reason}|${engine.explanation}"
    diagnostics[key]=(diagnostics[key]?:0)+1
    engine.decision
   }
   println("MULTI_COIN_DATASET symbol=${coin.name} bars1h=${h1.size} bars15m=${m15.size} bars5m=${m5.size}")
   diagnostics.toSortedMap().forEach{(reason,count)->println("MULTI_COIN_DIAGNOSTIC symbol=${coin.name} count=$count state=$reason")}
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

 @Test fun compareModeratelyRelaxedRangeProfileWithoutChangingProductionDefaults(){
  val root=File(System.getenv("MULTI_HISTORY_DIR")?:"build/multi-coin-history")
  val manifestJson=JSONObject(File(root,"manifest.json").readText())
  val symbolsArray=manifestJson.getJSONArray("symbols")
  val symbols=(0 until symbolsArray.length()).map{symbolsArray.getString(it)}
  val aggregates=MultiCoinMetricsAggregator()
  val experimentalConfig=AdaptiveEngineConfig(
   rangeSetup=RangeDetectionConfig(
    boundaryLookback=16,
    boundaryAtrTolerance=0.35,
    minimumWickBodyRatio=0.90,
    minimumVolumeRatio=0.60,
    minimumBodyAtr=0.08
   )
  )
  for(symbol in symbols){
   val coin=File(root,symbol)
   val h1=load(File(coin,"1h.csv"));val m15=load(File(coin,"15m.csv"));val m5=load(File(coin,"5m.csv"))
   val result=WalkForwardEvaluator.evaluate(m5,listOf(12,36,72),0.60,evaluationStep=12){history->
    val decisionTime=history.last().closeTime+1L
    AdaptivePredictionEngine.evaluate(
     AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history),
     experimentalConfig
    ).decision
   }
   result.horizons.forEach{row->
    println("MULTI_COIN_EXPERIMENT profile=range_moderate symbol=${coin.name} horizonMinutes=${row.horizonBars*5} candidates=${row.candidateCount} signals=${row.evaluated} waits=${row.waitCount} coveragePct=${row.coveragePercent} hitRatePct=${row.hitRatePercent} meanSignedReturnPct=${row.meanSignedReturnPercent}")
    aggregates.add(row)
   }
  }
  for(row in aggregates.results()){
   println("MULTI_COIN_EXPERIMENT_AGGREGATE profile=range_moderate horizonMinutes=${row.horizonBars*5} coins=${symbols.size} candidates=${row.candidateCount} signals=${row.evaluated} hits=${row.directionalHits} misses=${row.misses} neutral=${row.neutral} waits=${row.waitCount} coveragePct=${row.coveragePercent} hitRatePct=${row.hitRatePercent} meanSignedReturnPct=${row.meanSignedReturnPercent}")
  }
 }

 @Test fun isolateRangeFilterSensitivityOneParameterAtATime(){
  val root=File(System.getenv("MULTI_HISTORY_DIR")?:"build/multi-coin-history")
  val manifestJson=JSONObject(File(root,"manifest.json").readText())
  val symbolsArray=manifestJson.getJSONArray("symbols")
  val symbols=(0 until symbolsArray.length()).map{symbolsArray.getString(it)}
  val defaults=RangeDetectionConfig()
  val profiles=listOf(
   "lookback_16" to defaults.copy(boundaryLookback=16),
   "tolerance_035" to defaults.copy(boundaryAtrTolerance=0.35),
   "wick_090" to defaults.copy(minimumWickBodyRatio=0.90),
   "volume_060" to defaults.copy(minimumVolumeRatio=0.60),
   "body_008" to defaults.copy(minimumBodyAtr=0.08)
  )
  for((profile,rangeConfig) in profiles){
   val aggregates=MultiCoinMetricsAggregator()
   val config=AdaptiveEngineConfig(rangeSetup=rangeConfig)
   for(symbol in symbols){
    val coin=File(root,symbol)
    val h1=load(File(coin,"1h.csv"));val m15=load(File(coin,"15m.csv"));val m5=load(File(coin,"5m.csv"))
    val result=WalkForwardEvaluator.evaluate(m5,listOf(12,36,72),0.60,evaluationStep=12){history->
     val decisionTime=history.last().closeTime+1L
     AdaptivePredictionEngine.evaluate(
      AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history),config
     ).decision
    }
    result.horizons.forEach{aggregates.add(it)}
   }
   for(row in aggregates.results()){
    println("MULTI_COIN_ONE_FACTOR profile=$profile horizonMinutes=${row.horizonBars*5} coins=${symbols.size} candidates=${row.candidateCount} signals=${row.evaluated} hits=${row.directionalHits} misses=${row.misses} waits=${row.waitCount} coveragePct=${row.coveragePercent} hitRatePct=${row.hitRatePercent} meanSignedReturnPct=${row.meanSignedReturnPercent}")
   }
  }
 }

 @Test fun compareDefaultVsAtrToleranceOnNonOverlappingOutOfSampleWindows(){
  val root=File(System.getenv("MULTI_HISTORY_DIR")?:"build/multi-coin-history")
  val manifestJson=JSONObject(File(root,"manifest.json").readText())
  val symbolsArray=manifestJson.getJSONArray("symbols")
  val symbols=(0 until symbolsArray.length()).map{symbolsArray.getString(it)}
  val profiles=listOf(
   "default" to AdaptiveEngineConfig(),
   "tolerance_035" to AdaptiveEngineConfig(rangeSetup=RangeDetectionConfig(boundaryAtrTolerance=0.35))
  )
  for((profile,config) in profiles){
   for(horizon in listOf(12,36,72)){
    val aggregates=MultiCoinMetricsAggregator()
    for(symbol in symbols){
     val coin=File(root,symbol)
     val h1=load(File(coin,"1h.csv"));val m15=load(File(coin,"15m.csv"));val m5=load(File(coin,"5m.csv"))
     val result=WalkForwardEvaluator.evaluate(m5,listOf(horizon),0.60,evaluationStep=horizon){history->
      val decisionTime=history.last().closeTime+1L
      AdaptivePredictionEngine.evaluate(
       AdaptiveEngineInput(decisionTime,h1.filter{it.closeTime<decisionTime},m15.filter{it.closeTime<decisionTime},history),config
      ).decision
     }
     result.horizons.forEach{aggregates.add(it)}
    }
    for(row in aggregates.results()){
     println("MULTI_COIN_NONOVERLAP profile=$profile horizonMinutes=${row.horizonBars*5} coins=${symbols.size} candidates=${row.candidateCount} signals=${row.evaluated} hits=${row.directionalHits} misses=${row.misses} waits=${row.waitCount} coveragePct=${row.coveragePercent} hitRatePct=${row.hitRatePercent} meanSignedReturnPct=${row.meanSignedReturnPercent}")
    }
   }
  }
 }
}
