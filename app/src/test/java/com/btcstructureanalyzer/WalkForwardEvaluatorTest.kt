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
 @Test fun waitPredictionsAreExcludedFromHitRateButCountedInCoverage(){
  val r=WalkForwardEvaluator.evaluate(series(30),listOf(2),0.5){AdaptiveDecision.WAIT};val h=r.horizons.single()
  assertEquals(0,h.evaluated);assertEquals(null,h.hitRatePercent);assertEquals(0.0,h.coveragePercent!!,0.0001);assertEquals(h.candidateCount,h.waitCount)
 }
}
