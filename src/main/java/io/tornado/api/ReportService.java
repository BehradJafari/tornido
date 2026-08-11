package io.tornado.api;

import io.tornado.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ReportService {
    private static final java.math.BigDecimal MINIMUM_CORRECT_MOVE=new java.math.BigDecimal("0.003");
    private final PredictionRepository predictions;
    private final AnalysisRunRepository runs;
    public ReportService(PredictionRepository predictions,AnalysisRunRepository runs){this.predictions=predictions;this.runs=runs;}

    public List<CoinReport> coinReports(int minSamples,long horizon){
        Map<String,Map<String,Score>> scores=new TreeMap<>();
        for(Prediction p:filter(predictions.findAllGraded(),horizon)) scores.computeIfAbsent(p.getCoin().getSymbol(),x->new HashMap<>()).computeIfAbsent(p.getMethodName(),x->new Score()).add(p.getOutcome()==Outcome.CORRECT);
        List<CoinReport> result=new ArrayList<>();
        scores.forEach((coin,methods)->{var rows=methods.entrySet().stream().filter(e->e.getValue().total>=minSamples).map(e->new MethodAccuracy(e.getKey(),e.getValue().total,e.getValue().correct,e.getValue().accuracy())).sorted(Comparator.comparingDouble(MethodAccuracy::accuracy).reversed().thenComparing(Comparator.comparingLong(MethodAccuracy::samples).reversed())).toList();result.add(new CoinReport(coin,rows));});
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
        Map<String,List<Prediction>> latest=new HashMap<>();runs.findTopByOrderByCreatedAtDesc().ifPresent(r->predictions.findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(r.getId()).stream().filter(p->horizon==0||p.getHorizonSeconds()==horizon).forEach(p->latest.computeIfAbsent(p.getCoin().getSymbol(),x->new ArrayList<>()).add(p)));
        List<CoinOpportunity> coins=new ArrayList<>();
        byCoin.forEach((coin,rows)->{
            Map<String,Score> methodScores=new HashMap<>();rows.forEach(p->methodScores.computeIfAbsent(p.getMethodName(),x->new Score()).add(p.getOutcome()==Outcome.CORRECT));
            var bestMethod=methodScores.entrySet().stream().filter(e->e.getValue().total>=minSamples).max(Comparator.comparingDouble(e->wilson(e.getValue().correct,e.getValue().total))).orElse(null);
            var mixes=calculateMixes(rows,minSamples,3);var bestMix=mixes.isEmpty()?null:mixes.getFirst();
            long total=rows.size(),correct=rows.stream().filter(p->p.getOutcome()==Outcome.CORRECT).count();double adjusted=wilson(correct,total)*100;
            var live=latest.getOrDefault(coin,List.of());double upWeight=0,totalWeight=0;for(Prediction p:live){Score s=methodScores.get(p.getMethodName());double weight=s==null||s.total<minSamples?.5:Math.max(.1,s.accuracy()/100);totalWeight+=weight;if(p.getPredictedDirection()==Direction.UP)upWeight+=weight;}double upShare=totalWeight==0?.5:upWeight/totalWeight;String direction=upShare>=.5?"UP":"DOWN";double confidence=Math.max(upShare,1-upShare)*100;
            coins.add(new CoinOpportunity(coin,total,correct,total==0?0:correct*100.0/total,adjusted,bestMethod==null?null:bestMethod.getKey(),bestMethod==null?0:bestMethod.getValue().accuracy(),bestMix==null?List.of():bestMix.methods(),bestMix==null?0:bestMix.accuracy(),bestMix==null?0:bestMix.samples(),direction,confidence,live.size()));
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
        Set<String> selected=new TreeSet<>(request.methods());Map<GroupKey,List<Prediction>> groups=new HashMap<>();
        filter(predictions.findAllGraded(),request.horizon()).stream().filter(p->p.getAnalysisRun()!=null&&p.getCoin().getSymbol().equalsIgnoreCase(request.coin())&&selected.contains(p.getMethodName())).forEach(p->groups.computeIfAbsent(new GroupKey(p.getAnalysisRun().getId(),p.getCoin().getId(),p.getHorizonSeconds()),x->new ArrayList<>()).add(p));
        List<TradeCandidate> candidates=new ArrayList<>();for(List<Prediction> rows:groups.values()){Map<String,Prediction> byMethod=new HashMap<>();rows.forEach(p->byMethod.put(p.getMethodName(),p));if(!byMethod.keySet().containsAll(selected))continue;int ups=0;for(String method:selected)if(byMethod.get(method).getPredictedDirection()==Direction.UP)ups++;if(ups*2==selected.size())continue;Prediction sample=byMethod.get(selected.iterator().next());if(sample.getPriceAtGrading()==null)continue;boolean up=ups*2>selected.size();candidates.add(new TradeCandidate(sample.getPredictedAt(),up,sample.getPriceAtPrediction().doubleValue(),sample.getPriceAtGrading().doubleValue(),mixCorrect(sample,up)));}
        candidates.sort(Comparator.comparing(TradeCandidate::time));
        List<MoneyTrade> trades=new ArrayList<>();double totalPnl=0,peak=0,maxDrawdown=0;int wins=0,losses=0,liquidations=0,index=0;for(TradeCandidate c:candidates){double marketReturn=(c.exit()-c.entry())/c.entry(),directional=c.up()?marketReturn:-marketReturn,leveraged=directional*request.leverage();boolean liquidated=leveraged<=-1;double pnl=liquidated?-request.tradeAmount():request.tradeAmount()*leveraged;totalPnl+=pnl;peak=Math.max(peak,totalPnl);maxDrawdown=Math.max(maxDrawdown,peak-totalPnl);if(c.correct())wins++;else losses++;if(liquidated)liquidations++;trades.add(new MoneyTrade(++index,c.time(),c.up()?"LONG":"SHORT",c.entry(),c.exit(),marketReturn*100,pnl,totalPnl,liquidated));}
        int executed=trades.size();double committed=request.tradeAmount()*executed,winRate=executed==0?0:wins*100.0/executed,roi=committed==0?0:totalPnl*100/committed,average=executed==0?0:totalPnl/executed;return new MoneyReport(request.coin().toUpperCase(),request.horizon(),List.copyOf(selected),executed,request.tradeAmount(),request.leverage(),committed,totalPnl,committed+totalPnl,roi,wins,losses,winRate,liquidations,maxDrawdown,average,trades);
    }

    private List<MixAccuracy> calculateMixes(List<Prediction> source,int minSamples,int size){
        Map<GroupKey,List<Prediction>> groups=new HashMap<>();
        for(Prediction p:source) if(p.getAnalysisRun()!=null) groups.computeIfAbsent(new GroupKey(p.getAnalysisRun().getId(),p.getCoin().getId(),p.getHorizonSeconds()),x->new ArrayList<>()).add(p);
        Map<String,MixScore> scores=new HashMap<>();
        for(List<Prediction> group:groups.values()){
            Map<String,Prediction> byMethod=new TreeMap<>();group.forEach(p->byMethod.put(p.getMethodName(),p));List<String> names=new ArrayList<>(byMethod.keySet());
            Prediction sample=group.getFirst();
            choose(names,size,0,new ArrayList<>(),mix->{int ups=0;for(String name:mix)if(byMethod.get(name).getPredictedDirection()==Direction.UP)ups++;MixScore score=scores.computeIfAbsent(String.join("|",mix),x->new MixScore());score.totalPredictions++;boolean sameDirection=ups==0||ups==mix.size(),correct=mixCorrect(sample,ups*2>mix.size());if(sameDirection){score.sameDirectionPredictions++;if(correct)score.sameDirectionCorrect++;}if(ups*2==mix.size())return;score.samples++;if(correct)score.correct++;});
        }
        return scores.entrySet().stream().filter(e->e.getValue().samples>=minSamples).map(e->new MixAccuracy(Arrays.asList(e.getKey().split("\\|",-1)),e.getValue().totalPredictions,e.getValue().sameDirectionPredictions,e.getValue().sameDirectionCorrect,e.getValue().samples,e.getValue().correct,e.getValue().accuracy())).sorted(Comparator.comparingDouble(MixAccuracy::accuracy).reversed().thenComparing(Comparator.comparingLong(MixAccuracy::samples).reversed())).limit(100).toList();
    }
    private double wilson(long correct,long total){if(total==0)return 0;double z=1.96,p=(double)correct/total,z2=z*z;return (p+z2/(2*total)-z*Math.sqrt((p*(1-p)+z2/(4*total))/total))/(1+z2/total);}
    private void choose(List<String> names,int size,int start,List<String> selected,java.util.function.Consumer<List<String>> consumer){if(selected.size()==size){consumer.accept(List.copyOf(selected));return;}for(int i=start;i<=names.size()-(size-selected.size());i++){selected.add(names.get(i));choose(names,size,i+1,selected,consumer);selected.removeLast();}}
    private void validateMixSize(int size){if(size<2||size>4)throw new IllegalArgumentException("mix size must be 2, 3, or 4");}
    private List<Prediction> filter(List<Prediction> source,long horizon){return horizon==0?source:source.stream().filter(p->p.getHorizonSeconds()==horizon).toList();}
    private boolean mixCorrect(Prediction p,boolean predictedUp){if(p.getPriceAtGrading()==null)return false;var change=p.getPriceAtGrading().subtract(p.getPriceAtPrediction()).divide(p.getPriceAtPrediction(),12,java.math.RoundingMode.HALF_UP);return predictedUp?change.compareTo(MINIMUM_CORRECT_MOVE)>=0:change.compareTo(MINIMUM_CORRECT_MOVE.negate())<=0;}
    private static class Score {long total,correct;void add(boolean hit){total++;if(hit)correct++;}double accuracy(){return total==0?0:correct*100.0/total;}}
    private static class MixScore {long totalPredictions,sameDirectionPredictions,sameDirectionCorrect,samples,correct;double accuracy(){return samples==0?0:correct*100.0/samples;}}
    private record GroupKey(long runId,long coinId,long horizon){}
    private record TradeCandidate(java.time.Instant time,boolean up,double entry,double exit,boolean correct){}
    public record MethodAccuracy(String method,long samples,long correct,double accuracy){}
    public record CoinReport(String coin,List<MethodAccuracy> methods){}
    public record MixAccuracy(List<String> methods,long totalPredictions,long sameDirectionPredictions,long sameDirectionCorrect,long samples,long correct,double accuracy){}
    public record CoinMixReport(String coin,int size,List<MixAccuracy> mixes){}
    public record CoinOpportunity(String coin,long samples,long correct,double rawAccuracy,double valueScore,String bestMethod,double bestMethodAccuracy,List<String> bestMix,double bestMixAccuracy,long bestMixSamples,String currentDirection,double currentConfidence,int currentSignals){}
    public record SuperReport(java.time.Instant generatedAt,int minSamples,List<CoinOpportunity> coins,List<MixAccuracy> topMixes){}
    public record BestMixBySize(int size,MixAccuracy mix){}
    public record ExcelSliceRow(String coin,long horizon,List<BestMixBySize> bestMixes){}
    public record MoneyRequest(String coin,long horizon,List<String> methods,double tradeAmount,int leverage){}
    public record MoneyTrade(int number,java.time.Instant time,String side,double entryPrice,double exitPrice,double marketMovePercent,double pnl,double cumulativePnl,boolean liquidated){}
    public record MoneyReport(String coin,long horizon,List<String> methods,int executedTrades,double tradeAmount,int leverage,double totalMarginUsed,double grossPnl,double endingValue,double roiPercent,int wins,int losses,double winRate,int liquidations,double maxDrawdown,double averagePnlPerTrade,List<MoneyTrade> trades){}
}
