package com.btcstructureanalyzer
import kotlin.math.abs

data class HorizonEvaluation(val horizonBars:Int,val candidateCount:Int,val evaluated:Int,val directionalHits:Int,val misses:Int,val neutral:Int,val waitCount:Int,val coveragePercent:Double?,val hitRatePercent:Double?,val meanSignedReturnPercent:Double?,val maxAbsoluteReturnPercent:Double?)
data class WalkForwardEvaluation(val trainEndExclusive:Int,val testStartInclusive:Int,val testEndExclusive:Int,val horizons:List<HorizonEvaluation>,val notes:List<String>)

/** Chronological no-look-ahead evaluator. Future candles are used only to score outcomes. */
object WalkForwardEvaluator {
 fun evaluate(candles:List<Candle>,horizons:List<Int>,trainFraction:Double=0.60,evaluationStep:Int=1,predictor:(List<Candle>)->AdaptiveDecision):WalkForwardEvaluation {
  require(candles.size>=20);require(horizons.isNotEmpty()&&horizons.all{it>0}&&horizons.distinct().size==horizons.size);require(trainFraction in 0.1..0.9);require(evaluationStep>=1)
  val interval=candles[1].openTime-candles[0].openTime
  require(interval>0L) { "Candle interval must be positive" }
  require(candles.all{c->c.openTime>=0L&&c.closeTime-c.openTime==interval-1L&&c.open.isFinite()&&c.high.isFinite()&&c.low.isFinite()&&c.close.isFinite()&&c.volume.isFinite()&&c.close>0.0&&c.open>0.0&&c.high>=maxOf(c.open,c.close)&&c.low<=minOf(c.open,c.close)&&c.low>0.0&&c.volume>=0.0})
  require(candles.zipWithNext().all{(a,b)->b.openTime-a.openTime==interval && b.closeTime>a.closeTime}) {
   "Candle series must be strictly ordered and gap-free"
  }
  val split=(candles.size*trainFraction).toInt().coerceIn(1,candles.size-1);val smallest=horizons.min()
  // Common decision window ends where the shortest horizon still has a known outcome.
  // Longer horizons independently exclude decisions without a complete future outcome.
  val decisionEndExclusive=candles.size-smallest
  require(horizons.all{h->(split until decisionEndExclusive step evaluationStep).any{it<candles.size-h}}) { "Each horizon must have at least one eligible test decision" }
  val decisionIndices=(split until decisionEndExclusive step evaluationStep).toList()
  val predictions=decisionIndices.associateWith{i->predictor(candles.subList(0,i+1).toList())}
  val rows=horizons.map{h->
   var evaluated=0;var hits=0;var misses=0;var neutral=0;var waits=0
   val signedReturns=mutableListOf<Double>();val absReturns=mutableListOf<Double>()
   val candidates=decisionIndices.count{it<candles.size-h}
   for(i in decisionIndices.filter{it<candles.size-h}){
    val decision=predictions.getValue(i)
    if(decision==AdaptiveDecision.WAIT){waits++;continue}
    val raw=(candles[i+h].close/candles[i].close-1.0)*100.0
    require(raw.isFinite()) { "Non-finite forward return at candle index $i, horizon $h" }
    val signed=if(decision==AdaptiveDecision.LONG)raw else -raw
    evaluated++;signedReturns.add(signed);absReturns.add(abs(raw))
    when{signed>0.0->hits++;signed<0.0->misses++;else->neutral++}
   }
   HorizonEvaluation(h,candidates,evaluated,hits,misses,neutral,waits,if(candidates>0)evaluated.toDouble()/candidates*100.0 else null,if(evaluated>0)hits.toDouble()/evaluated*100.0 else null,signedReturns.takeIf{it.isNotEmpty()}?.average(),absReturns.maxOrNull())
  }
  return WalkForwardEvaluation(split,split,decisionEndExclusive,rows,listOf("Prediktor hanya menerima data sampai candle keputusan; candle masa depan hanya dipakai untuk scoring.","Split kronologis; tidak ada pengacakan data.","trainFraction hanya menentukan awal periode evaluasi; tidak ada pelatihan model machine learning.","testEndExclusive adalah batas akhir indeks keputusan; setiap horizon memiliki jumlah kandidat eligible sendiri.","Setiap keputusan dihitung sekali lalu dinilai pada seluruh horizon yang relevan.","Evaluation step=$evaluationStep candle; jarak sampling mengurangi beban komputasi, bukan mengubah horizon outcome.","Coverage adalah proporsi keputusan LONG/SHORT dari seluruh kandidat; WAIT dihitung terpisah.","Return diukur pada close horizon; tidak mensimulasikan biaya, slippage, atau eksekusi.","Hit rate bukan probabilitas kemenangan dan tidak membuktikan performa live."))
 }

 /**
  * Evaluates only decisions and complete forward outcomes inside one chronological segment.
  * All candles before a decision remain available as warm-up history; no candle at or after
  * the decision time is passed to the predictor.
  */
 fun evaluateSegment(
  candles:List<Candle>,
  horizons:List<Int>,
  decisionStartTimeInclusive:Long,
  decisionEndTimeExclusive:Long,
  evaluationStep:Int=1,
  predictor:(List<Candle>)->AdaptiveDecision
 ):WalkForwardEvaluation {
  require(candles.size>=20)
  require(horizons.isNotEmpty()&&horizons.all{it>0}&&horizons.distinct().size==horizons.size)
  require(evaluationStep>=1)
  require(decisionStartTimeInclusive>0L&&decisionEndTimeExclusive>decisionStartTimeInclusive)
  val interval=candles[1].openTime-candles[0].openTime
  require(interval>0L) { "Candle interval must be positive" }
  require(candles.all{c->c.openTime>=0L&&c.closeTime-c.openTime==interval-1L&&c.open.isFinite()&&c.high.isFinite()&&c.low.isFinite()&&c.close.isFinite()&&c.volume.isFinite()&&c.close>0.0&&c.open>0.0&&c.high>=maxOf(c.open,c.close)&&c.low<=minOf(c.open,c.close)&&c.low>0.0&&c.volume>=0.0})
  require(candles.zipWithNext().all{(a,b)->b.openTime-a.openTime==interval&&b.closeTime>a.closeTime}) {
   "Candle series must be strictly ordered and gap-free"
  }
  val decisionIndices=candles.indices.filter{i->
   val decisionTime=candles[i].closeTime+1L
   decisionTime>=decisionStartTimeInclusive&&decisionTime<decisionEndTimeExclusive&&
    horizons.any{h->i+h<candles.size&&candles[i+h].closeTime+1L<=decisionEndTimeExclusive}
  }.filterIndexed{position,_->position%evaluationStep==0}
  require(horizons.all{h->decisionIndices.any{i->i+h<candles.size&&candles[i+h].closeTime+1L<=decisionEndTimeExclusive}}) {
   "Each horizon must have at least one eligible decision fully contained in this segment"
  }
  val predictions=decisionIndices.associateWith{i->predictor(candles.subList(0,i+1).toList())}
  val rows=horizons.map{h->
   val eligible=decisionIndices.filter{i->i+h<candles.size&&candles[i+h].closeTime+1L<=decisionEndTimeExclusive}
   var evaluated=0;var hits=0;var misses=0;var neutral=0;var waits=0
   val signedReturns=mutableListOf<Double>();val absReturns=mutableListOf<Double>()
   for(i in eligible){
    val decision=predictions.getValue(i)
    if(decision==AdaptiveDecision.WAIT){waits++;continue}
    val raw=(candles[i+h].close/candles[i].close-1.0)*100.0
    require(raw.isFinite()) { "Non-finite forward return at candle index $i, horizon $h" }
    val signed=if(decision==AdaptiveDecision.LONG)raw else -raw
    evaluated++;signedReturns.add(signed);absReturns.add(abs(raw))
    when{signed>0.0->hits++;signed<0.0->misses++;else->neutral++}
   }
   HorizonEvaluation(h,eligible.size,evaluated,hits,misses,neutral,waits,
    if(eligible.isNotEmpty())evaluated.toDouble()/eligible.size*100.0 else null,
    if(evaluated>0)hits.toDouble()/evaluated*100.0 else null,
    signedReturns.takeIf{it.isNotEmpty()}?.average(),absReturns.maxOrNull())
  }
  val first=decisionIndices.minOrNull()?:0
  val end=decisionIndices.maxOrNull()?.plus(1)?:first
  return WalkForwardEvaluation(first,first,end,rows,listOf(
   "Chronological segment: decisions are restricted to [$decisionStartTimeInclusive, $decisionEndTimeExclusive).",
   "All earlier candles are available as warm-up; predictor receives data only through each decision candle.",
   "A forward outcome is scored only when its closing timestamp is at or before the segment end.",
   "No model training is performed; this is a chronological evaluation window.",
   "Evaluation step=$evaluationStep candle; each decision is predicted once and reused across eligible horizons.",
   "Coverage counts LONG/SHORT decisions against eligible candidates; WAIT is counted separately.",
   "Returns are close-to-close and exclude fees, slippage, execution and path risk; hit rate is not a win probability."
  ))
 }

}
