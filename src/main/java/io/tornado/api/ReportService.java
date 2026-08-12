package io.tornado.api;

import io.tornado.persistence.*;
import io.tornado.datafetch.BinanceMarketDataClient;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ReportService {
    private static final java.math.BigDecimal MINIMUM_CORRECT_MOVE=new java.math.BigDecimal("0.003");
    private final PredictionRepository predictions;
    private final AnalysisRunRepository runs;
    private final BinanceMarketDataClient market;
    public ReportService(PredictionRepository predictions,AnalysisRunRepository runs,BinanceMarketDataClient market){this.predictions=predictions;this.runs=runs;this.market=market;}

    public List<CoinReport> coinReports(int minSamples,long horizon){
        Map<String,Map<String,Score>> scores=new TreeMap<>();
        for(Prediction p:filter(predictions.findAllGraded(),horizon)) scores.computeIfAbsent(p.getCoin().getSymbol(),x->new HashMap<>()).computeIfAbsent(p.getMethodName(),x->new Score()).add(p);
        List<CoinReport> result=new ArrayList<>();
        scores.forEach((coin,methods)->{var rows=methods.entrySet().stream().filter(e->e.getValue().total>=minSamples).map(e->new MethodAccuracy(e.getKey(),e.getValue().total,e.getValue().targetCorrect,e.getValue().targetHitRate(),e.getValue().directionalCorrect,e.getValue().directionalAccuracy())).sorted(Comparator.comparingDouble(MethodAccuracy::targetHitRate).reversed().thenComparing(Comparator.comparingLong(MethodAccuracy::samples).reversed())).toList();result.add(new CoinReport(coin,rows));});
        return result;
    }

    public List<MixAccuracy> mixReports(int minSamples,int size,long horizon){
        validateMixSize(size);
        return calculateMixes(filter(predictions.findAllGraded(),horizon),minSamples,size);
    }

    public List<CoinMixReport> coinMixReports(int minSamples,int size,long horizon){
        validateMixSize(size);Map<String,List<Prediction>> byCoin=new TreeMap<>();filter(predictions.findAllGraded(),horizon).forEach(p->byCoin.computeIfAbsent(p.getCoin().getSymbol(),x->new ArrayList<>()).add(p));
        return byCoin.entrySet().stream().map(e->new CoinMixReport(e.getKey(),size,calculateMixes(e.getValue(),minSamples,size))).toList();
    }

    public SuperReport superReport(int minSamples,long horizon){
        List<Prediction> graded=filter(predictions.findAllGraded(),horizon);
        Map<String,List<Prediction>> byCoin=new TreeMap<>();graded.forEach(p->byCoin.computeIfAbsent(p.getCoin().getSymbol(),x->new ArrayList<>()).add(p));
        Map<String,List<Prediction>> latest=new HashMap<>();runs.findTopByOrderByCreatedAtDesc().ifPresent(r->predictions.findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(r.getId()).stream().filter(p->p.getSignalVersion()==2&&p.getHorizonSeconds()==horizon).forEach(p->latest.computeIfAbsent(p.getCoin().getSymbol(),x->new ArrayList<>()).add(p)));
        List<CoinOpportunity> coins=new ArrayList<>();
        byCoin.forEach((coin,rows)->{
            Map<String,Score> methodScores=new HashMap<>();rows.forEach(p->methodScores.computeIfAbsent(p.getMethodName(),x->new Score()).add(p));
            var bestMethod=methodScores.entrySet().stream().filter(e->e.getValue().total>=minSamples).max(Comparator.comparingDouble(e->wilson(e.getValue().targetCorrect,e.getValue().total))).orElse(null);
            var mixes=calculateMixes(rows,minSamples,3);var bestMix=mixes.isEmpty()?null:mixes.getFirst();
            long total=rows.size(),correct=rows.stream().filter(p->p.getOutcome()==Outcome.CORRECT).count(),directionalCorrect=rows.stream().filter(p->directionCorrect(p,p.getPredictedDirection()==Direction.UP)).count();double adjusted=wilson(correct,total)*100;
            var live=latest.getOrDefault(coin,List.of());double upWeight=0,totalWeight=0;int weightedSignals=0;for(Prediction p:live){Score s=methodScores.get(p.getMethodName());double weight=s==null||s.total<minSamples?0:Math.max(0,wilson(s.targetCorrect,s.total)-.5);if(weight==0)continue;weightedSignals++;totalWeight+=weight;if(p.getPredictedDirection()==Direction.UP)upWeight+=weight;}double upShare=totalWeight==0?.5:upWeight/totalWeight;String direction=totalWeight==0?"WAIT":upShare>=.5?"UP":"DOWN";double strength=totalWeight==0?0:Math.max(upShare,1-upShare)*100;
            coins.add(new CoinOpportunity(coin,total,correct,total==0?0:correct*100.0/total,directionalCorrect,total==0?0:directionalCorrect*100.0/total,adjusted,bestMethod==null?null:bestMethod.getKey(),bestMethod==null?0:bestMethod.getValue().targetHitRate(),bestMix==null?List.of():bestMix.methods(),bestMix==null?0:bestMix.targetHitRate(),bestMix==null?0:bestMix.samples(),direction,strength,weightedSignals));
        });
        coins.sort(Comparator.comparingDouble(CoinOpportunity::valueScore).reversed().thenComparing(Comparator.comparingLong(CoinOpportunity::samples).reversed()));
        return new SuperReport(java.time.Instant.now(),minSamples,coins,calculateMixes(graded,minSamples,3).stream().limit(10).toList());
    }

    public List<ExcelSliceRow> superExcelRows(){
        Map<String,Map<Long,List<Prediction>>> grouped=new TreeMap<>();
        for(Prediction p:predictions.findAllGraded())grouped.computeIfAbsent(p.getCoin().getSymbol(),x->new TreeMap<>()).computeIfAbsent(p.getHorizonSeconds(),x->new ArrayList<>()).add(p);
        List<ExcelSliceRow> result=new ArrayList<>();
        grouped.forEach((coin,slices)->slices.forEach((horizon,rows)->{List<BestMixBySize> best=new ArrayList<>();for(int size=1;size<=6;size++){var mixes=calculateMixes(rows,1,size);best.add(new BestMixBySize(size,mixes.isEmpty()?null:mixes.getFirst()));}result.add(new ExcelSliceRow(coin,horizon,best));}));
        return result;
    }

    public MoneyReport moneyReport(MoneyRequest request){
        if(request.methods()==null||request.methods().size()<2||request.methods().size()>8)throw new IllegalArgumentException("select between 2 and 8 methods");
        if(request.tradeAmount()<=0||request.tradeAmount()>1_000_000)throw new IllegalArgumentException("trade amount must be positive and at most 1,000,000 USDT");
        if(request.leverage()<1||request.leverage()>125)throw new IllegalArgumentException("leverage must be between 1x and 125x");
        validateCost("taker fee",request.takerFeePercent());validateCost("slippage",request.slippagePercent());validateCost("spread",request.spreadPercent());validateCost("funding",request.fundingRatePercent());
        Set<String> selected=new TreeSet<>(request.methods());Map<GroupKey,List<Prediction>> groups=new HashMap<>();
        if(selected.size()<2)throw new IllegalArgumentException("select at least two distinct methods");
        filter(predictions.findAllGraded(),request.horizon()).stream().filter(p->p.getAnalysisRun()!=null&&p.getCoin().getSymbol().equalsIgnoreCase(request.coin())&&selected.contains(p.getMethodName())).forEach(p->groups.computeIfAbsent(new GroupKey(p.getAnalysisRun().getId(),p.getCoin().getId(),p.getHorizonSeconds()),x->new ArrayList<>()).add(p));
        List<TradeCandidate> candidates=new ArrayList<>();for(List<Prediction> rows:groups.values()){Map<String,Prediction> byMethod=new HashMap<>();rows.forEach(p->byMethod.put(p.getMethodName(),p));if(!byMethod.keySet().containsAll(selected))continue;int ups=0;for(String method:selected)if(byMethod.get(method).getPredictedDirection()==Direction.UP)ups++;if(ups*2==selected.size())continue;Prediction sample=byMethod.get(selected.iterator().next());if(sample.getPriceAtGrading()==null)continue;boolean up=ups*2>selected.size();candidates.add(new TradeCandidate(sample.getPredictedAt(),sample.getPredictedAt().plusSeconds(sample.getHorizonSeconds()),sample.getCoin().getPair(),up,sample.getPriceAtPrediction().doubleValue(),sample.getPriceAtGrading().doubleValue(),mixCorrect(sample,up)));}
        candidates.sort(Comparator.comparing(TradeCandidate::time));
        List<MoneyTrade> trades=new ArrayList<>();double grossTotal=0,netTotal=0,totalCosts=0,realizedPeak=0,realizedDrawdown=0;int profitable=0,losing=0,breakEven=0,targetHits=0,liquidations=0,index=0;double notional=request.tradeAmount()*request.leverage();for(TradeCandidate c:candidates){double marketReturn=(c.exit()-c.entry())/c.entry(),directional=c.up()?marketReturn:-marketReturn,leveraged=directional*request.leverage();var range=market.priceRange(c.pair(),c.time(),c.target());double liquidationPrice=c.up()?c.entry()*(1.0-1.0/request.leverage()):c.entry()*(1.0+1.0/request.leverage());boolean liquidated=c.up()?range.low().doubleValue()<=liquidationPrice:range.high().doubleValue()>=liquidationPrice;double gross=liquidated?-request.tradeAmount():request.tradeAmount()*leveraged;double costs=notional*((2*request.takerFeePercent()+2*request.slippagePercent()+request.spreadPercent()+request.fundingRatePercent())/100.0);double net=liquidated?-request.tradeAmount():Math.max(-request.tradeAmount(),gross-costs);grossTotal+=gross;totalCosts+=liquidated?0:costs;netTotal+=net;realizedPeak=Math.max(realizedPeak,netTotal);realizedDrawdown=Math.max(realizedDrawdown,realizedPeak-netTotal);if(net>1e-9)profitable++;else if(net< -1e-9)losing++;else breakEven++;if(c.targetHit())targetHits++;if(liquidated)liquidations++;trades.add(new MoneyTrade(++index,c.time(),c.target(),c.up()?"LONG":"SHORT",c.entry(),c.exit(),marketReturn*100,liquidationPrice,gross,liquidated?0:costs,net,netTotal,liquidated));}
        int executed=trades.size(),peakConcurrent=peakConcurrency(candidates);double totalMargin=request.tradeAmount()*executed,peakMargin=request.tradeAmount()*peakConcurrent,profitWinRate=executed==0?0:profitable*100.0/executed,targetHitRate=executed==0?0:targetHits*100.0/executed,pnlToPeakMargin=peakMargin==0?0:netTotal*100/peakMargin,average=executed==0?0:netTotal/executed;return new MoneyReport(request.coin().toUpperCase(),request.horizon(),List.copyOf(selected),"INDEPENDENT_TRADES_SIMPLE_LIQUIDATION",executed,request.tradeAmount(),request.leverage(),totalMargin,peakConcurrent,peakMargin,grossTotal,totalCosts,netTotal,pnlToPeakMargin,profitable,losing,breakEven,profitWinRate,targetHits,executed-targetHits,targetHitRate,liquidations,realizedDrawdown,average,trades);
    }
    private void validateCost(String name,double value){if(value<0||value>5)throw new IllegalArgumentException(name+" percent must be between 0 and 5");}
    private int peakConcurrency(List<TradeCandidate> candidates){int peak=0;for(TradeCandidate start:candidates){int active=0;for(TradeCandidate candidate:candidates)if(!candidate.time().isAfter(start.time())&&candidate.target().isAfter(start.time()))active++;peak=Math.max(peak,active);}return peak;}

    private List<MixAccuracy> calculateMixes(List<Prediction> source,int minSamples,int size){
        Map<GroupKey,List<Prediction>> groups=new HashMap<>();
        for(Prediction p:source) if(p.getAnalysisRun()!=null) groups.computeIfAbsent(new GroupKey(p.getAnalysisRun().getId(),p.getCoin().getId(),p.getHorizonSeconds()),x->new ArrayList<>()).add(p);
        Map<String,MixScore> scores=new HashMap<>();
        for(List<Prediction> group:groups.values()){
            Map<String,Prediction> byMethod=new TreeMap<>();group.forEach(p->byMethod.put(p.getMethodName(),p));List<String> names=new ArrayList<>(byMethod.keySet());
            Prediction sample=group.getFirst();
            choose(names,size,0,new ArrayList<>(),mix->{int ups=0;for(String name:mix)if(byMethod.get(name).getPredictedDirection()==Direction.UP)ups++;MixScore score=scores.computeIfAbsent(String.join("|",mix),x->new MixScore());score.totalPredictions++;boolean sameDirection=ups==0||ups==mix.size(),predictedUp=ups*2>mix.size(),targetHit=mixCorrect(sample,predictedUp),directionHit=directionCorrect(sample,predictedUp);if(sameDirection){score.sameDirectionPredictions++;if(targetHit)score.sameDirectionCorrect++;}if(ups*2==mix.size())return;score.samples++;if(targetHit)score.targetCorrect++;if(directionHit)score.directionalCorrect++;});
        }
        return scores.entrySet().stream().filter(e->e.getValue().samples>=minSamples).map(e->new MixAccuracy(Arrays.asList(e.getKey().split("\\|",-1)),e.getValue().totalPredictions,e.getValue().sameDirectionPredictions,e.getValue().sameDirectionCorrect,e.getValue().samples,e.getValue().targetCorrect,e.getValue().targetHitRate(),e.getValue().directionalCorrect,e.getValue().directionalAccuracy())).sorted(Comparator.comparingDouble(MixAccuracy::targetHitRate).reversed().thenComparing(Comparator.comparingLong(MixAccuracy::samples).reversed())).limit(100).toList();
    }
    private double wilson(long correct,long total){if(total==0)return 0;double z=1.96,p=(double)correct/total,z2=z*z;return (p+z2/(2*total)-z*Math.sqrt((p*(1-p)+z2/(4*total))/total))/(1+z2/total);}
    private void choose(List<String> names,int size,int start,List<String> selected,java.util.function.Consumer<List<String>> consumer){if(selected.size()==size){consumer.accept(List.copyOf(selected));return;}for(int i=start;i<=names.size()-(size-selected.size());i++){selected.add(names.get(i));choose(names,size,i+1,selected,consumer);selected.removeLast();}}
    private void validateMixSize(int size){if(size<2||size>4)throw new IllegalArgumentException("mix size must be 2, 3, or 4");}
    private List<Prediction> filter(List<Prediction> source,long horizon){HorizonPolicy.requireSupported(horizon);return source.stream().filter(p->p.getHorizonSeconds()==horizon).toList();}
    private boolean mixCorrect(Prediction p,boolean predictedUp){if(p.getPriceAtGrading()==null)return false;var change=p.getPriceAtGrading().subtract(p.getPriceAtPrediction()).divide(p.getPriceAtPrediction(),12,java.math.RoundingMode.HALF_UP);return predictedUp?change.compareTo(MINIMUM_CORRECT_MOVE)>=0:change.compareTo(MINIMUM_CORRECT_MOVE.negate())<=0;}
    private boolean directionCorrect(Prediction p,boolean predictedUp){if(p.getPriceAtGrading()==null)return false;int comparison=p.getPriceAtGrading().compareTo(p.getPriceAtPrediction());return predictedUp?comparison>0:comparison<0;}
    private class Score {long total,targetCorrect,directionalCorrect;void add(Prediction p){total++;if(p.getOutcome()==Outcome.CORRECT)targetCorrect++;if(directionCorrect(p,p.getPredictedDirection()==Direction.UP))directionalCorrect++;}double targetHitRate(){return total==0?0:targetCorrect*100.0/total;}double directionalAccuracy(){return total==0?0:directionalCorrect*100.0/total;}}
    private static class MixScore {long totalPredictions,sameDirectionPredictions,sameDirectionCorrect,samples,targetCorrect,directionalCorrect;double targetHitRate(){return samples==0?0:targetCorrect*100.0/samples;}double directionalAccuracy(){return samples==0?0:directionalCorrect*100.0/samples;}}
    private record GroupKey(long runId,long coinId,long horizon){}
    private record TradeCandidate(java.time.Instant time,java.time.Instant target,String pair,boolean up,double entry,double exit,boolean targetHit){}
    public record MethodAccuracy(String method,long samples,long targetCorrect,double targetHitRate,long directionalCorrect,double directionalAccuracy){}
    public record CoinReport(String coin,List<MethodAccuracy> methods){}
    public record MixAccuracy(List<String> methods,long totalPredictions,long sameDirectionPredictions,long sameDirectionCorrect,long samples,long targetCorrect,double targetHitRate,long directionalCorrect,double directionalAccuracy){}
    public record CoinMixReport(String coin,int size,List<MixAccuracy> mixes){}
    public record CoinOpportunity(String coin,long samples,long targetCorrect,double targetHitRate,long directionalCorrect,double directionalAccuracy,double valueScore,String bestMethod,double bestMethodTargetHitRate,List<String> bestMix,double bestMixTargetHitRate,long bestMixSamples,String currentDirection,double consensusStrength,int weightedSignals){}
    public record SuperReport(java.time.Instant generatedAt,int minSamples,List<CoinOpportunity> coins,List<MixAccuracy> topMixes){}
    public record BestMixBySize(int size,MixAccuracy mix){}
    public record ExcelSliceRow(String coin,long horizon,List<BestMixBySize> bestMixes){}
    public record MoneyRequest(String coin,long horizon,List<String> methods,double tradeAmount,int leverage,double takerFeePercent,double slippagePercent,double spreadPercent,double fundingRatePercent){}
    public record MoneyTrade(int number,java.time.Instant time,java.time.Instant targetTime,String side,double entryPrice,double exitPrice,double marketMovePercent,double approximateLiquidationPrice,double grossPnl,double costs,double netPnl,double cumulativeNetPnl,boolean liquidated){}
    public record MoneyReport(String coin,long horizon,List<String> methods,String simulationModel,int executedTrades,double tradeAmount,int leverage,double totalMarginAllocated,int peakConcurrentTrades,double peakMarginRequired,double grossPnl,double totalCosts,double netPnl,double netPnlToPeakConcurrentMarginPercent,int profitableTrades,int losingTrades,int breakEvenTrades,double profitWinRate,int targetHits,int targetMisses,double targetHitRate,int liquidations,double realizedPnlDrawdown,double averageNetPnlPerTrade,List<MoneyTrade> trades){}
}
