package com.btcstructureanalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class WalkForwardEvaluatorTest {
 private fun series(size:Int)=(0 until size).map{i->val p=100.0+i;Candle(i*60_000L,p,p+1,p-1,p,10.0,(i+1)*60_000L-1)}
 @Test fun predictorNeverReceivesFutureCandles(){
  val seen=mutableListOf<Int>();val r=WalkForwardEvaluator.evaluate(series(40),listOf(1,3),0.5){h->seen.add(h.size);AdaptiveDecision.LONG}
  assertTrue(seen.all{it<40});assertEquals(20,r.testStartInclusive);assertEquals(100.0,r.horizons.first().hitRatePercent!!,0.0001);assertEquals(100.0,r.horizons.first().coveragePercent!!,0.0001);assertEquals(0,r.horizons.first().waitCount)
 }
 @Test fun reportsCommonDecisionWindowAndHorizonSpecificCandidateCounts(){
  val data=series(50)
  val result=WalkForwardEvaluator.evaluate(data,listOf(1,3,6),0.5,evaluationStep=2){AdaptiveDecision.LONG}
  assertEquals(49,result.testEndExclusive)
  assertEquals(12,result.horizons[0].candidateCount)
  assertEquals(11,result.horizons[1].candidateCount)
  assertEquals(10,result.horizons[2].candidateCount)
 }


 @Test fun rejectsHorizonWithoutEligibleTestDecisions(){
  try{
   WalkForwardEvaluator.evaluate(series(30),listOf(1,20),0.5){AdaptiveDecision.LONG}
   throw AssertionError("Every horizon must have at least one eligible test decision")
  }catch(_:IllegalArgumentException){
   // Expected.
  }
 }

 @Test fun rejectsMissingCandleIntervalsBecauseHorizonsAreMeasuredInBars(){
  val data=series(30).toMutableList()
  data.removeAt(12)
  try{
   WalkForwardEvaluator.evaluate(data,listOf(2),0.5){AdaptiveDecision.LONG}
   throw AssertionError("A candle gap must be rejected for bar-based horizons")
  }catch(_:IllegalArgumentException){
   // Expected.
  }
 }

 @Test fun rejectsInconsistentCandleDurations(){
  val data=series(30).toMutableList()
  data[15]=data[15].copy(closeTime=data[15].closeTime-1L)
  try{
   WalkForwardEvaluator.evaluate(data,listOf(2),0.5){AdaptiveDecision.LONG}
   throw AssertionError("Inconsistent candle duration must be rejected")
  }catch(_:IllegalArgumentException){
   // Expected.
  }
 }

 @Test fun waitPredictionsAreExcludedFromHitRateButCountedInCoverage(){
  val r=WalkForwardEvaluator.evaluate(series(30),listOf(2),0.5){AdaptiveDecision.WAIT};val h=r.horizons.single()
  assertEquals(0,h.evaluated);assertEquals(null,h.hitRatePercent);assertEquals(0.0,h.coveragePercent!!,0.0001);assertEquals(h.candidateCount,h.waitCount)
 }
 @Test fun evaluatesEachDecisionOnlyOnceAcrossAllHorizons(){
  var calls=0
  val data=series(50)
  val result=WalkForwardEvaluator.evaluate(data,listOf(1,3,6),0.5,evaluationStep=2){
   calls++
   AdaptiveDecision.LONG
  }
  val expected=(25 until data.size-1 step 2).count()
  assertEquals(expected,calls)
  assertEquals(expected,result.horizons.first().candidateCount)
  assertTrue(result.horizons[1].candidateCount<=result.horizons[0].candidateCount)
  assertTrue(result.horizons[2].candidateCount<=result.horizons[1].candidateCount)
 }

 @Test fun rejectsInvalidCandleTimeOrdering(){
  val data=series(30).toMutableList()
  data[12]=data[12].copy(closeTime=data[12].openTime)
  try{
   WalkForwardEvaluator.evaluate(data,listOf(1),0.5){AdaptiveDecision.LONG}
   throw AssertionError("Invalid candle time range must be rejected")
  }catch(_:IllegalArgumentException){
   // Expected.
  }
 }

 @Test fun segmentEvaluatorKeepsWarmupAndContainsEveryForwardOutcome(){
  val data=series(60)
  val start=data[20].closeTime+1L
  val end=data[40].closeTime+1L
  var calls=0
  val result=WalkForwardEvaluator.evaluateSegment(data,listOf(1,3),start,end,evaluationStep=2){
   calls++
   assertTrue(it.last().closeTime<end)
   AdaptiveDecision.LONG
  }
  assertEquals(10,result.horizons[0].candidateCount)
  assertEquals(9,result.horizons[1].candidateCount)
  assertEquals(10,calls)
  assertTrue(result.notes.any{it.contains("warm-up")})
 }

 @Test fun segmentEvaluatorRejectsHorizonWithoutContainedOutcome(){
  val data=series(40)
  val start=data[30].closeTime+1L
  val end=data[32].closeTime+1L
  try{
   WalkForwardEvaluator.evaluateSegment(data,listOf(6),start,end){AdaptiveDecision.LONG}
   throw AssertionError("Segment must reject horizons that cannot finish inside its boundary")
  }catch(_:IllegalArgumentException){
   // Expected.
  }
 }

}
