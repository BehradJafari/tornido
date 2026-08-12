package io.tornado.scheduler;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
import io.tornado.strategies.StrategyDefinition;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal; import java.time.*; import java.util.*;

@Service
public class PredictionService {
    private static final Logger log=LoggerFactory.getLogger(PredictionService.class);
    public static final List<Duration> AUTOMATIC_HORIZONS=List.of(
            Duration.ofMinutes(1),Duration.ofMinutes(15),Duration.ofMinutes(30),
            Duration.ofHours(1),Duration.ofHours(4),Duration.ofHours(12),Duration.ofHours(24));
    private static final BigDecimal MINIMUM_CORRECT_MOVE=new BigDecimal("0.003");
    private static final int MAX_GRADING_ATTEMPTS=5;
    private final CoinRepository coins; private final PredictionRepository predictions; private final AnalysisRunRepository runs; private final BinanceMarketDataClient market;
    public PredictionService(CoinRepository c,PredictionRepository p,AnalysisRunRepository r,BinanceMarketDataClient m){coins=c;predictions=p;runs=r;market=m;}
    public RunResult snapshot(){return snapshot("Scheduled analysis "+Instant.now());}
    public RunResult snapshot(String name){AnalysisRun run=runs.save(new AnalysisRun(name,Duration.ZERO));int saved=0;List<String> errors=new ArrayList<>();
        for(Coin coin:coins.findAllByActiveTrueOrderBySymbol())try{var series=market.candles(coin.getPair());var last=series.getLastBar();Instant signalAt=last.getEndTime();BigDecimal signalPrice=new BigDecimal(last.getClosePrice().toString());BigDecimal executionPrice=market.price(coin.getPair());Instant executionAt=Instant.now();for(var def:StrategyDefinition.values()){var signal=def.evaluate(series);if(signal.direction()==Direction.NEUTRAL)continue;for(Duration horizon:AUTOMATIC_HORIZONS){predictions.save(new Prediction(run,coin,def.code(),def.version(),def.label(),signalAt,signalPrice,executionAt,executionPrice,signal.direction(),horizon,market.candleInterval()));saved++;}}}catch(Exception e){log.error("Snapshot failed for {}",coin.getPair(),e);errors.add(coin.getPair()+": "+e.getMessage());}
        run.complete(errors.size(),saved);runs.save(run);return new RunResult(run.getId(),saved,errors);
    }
    @Transactional public int gradeDue(){int count=0;Instant now=Instant.now();Map<TargetKey,PriceLookup> prices=new HashMap<>();Map<TargetKey,List<Prediction>> due=new LinkedHashMap<>();for(Prediction p:predictions.findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome.PENDING,2)){Instant target=p.getPredictedAt().plusSeconds(p.getHorizonSeconds());if(!target.isAfter(now))due.computeIfAbsent(new TargetKey(p.getCoin().getPair(),target),x->new ArrayList<>()).add(p);}for(var entry:due.entrySet()){TargetKey key=entry.getKey();PriceLookup lookup=prices.computeIfAbsent(key,k->lookup(k));if(lookup.price()!=null){for(Prediction p:entry.getValue()){p.grade(lookup.price().price(),lookup.price().observedAt(),now,MINIMUM_CORRECT_MOVE);count++;}continue;}int terminal=0;for(Prediction p:entry.getValue()){p.recordGradingError(lookup.error());if(p.getGradingAttempts()>=MAX_GRADING_ATTEMPTS){p.markUngradable(now);terminal++;}}int attempt=entry.getValue().stream().mapToInt(Prediction::getGradingAttempts).max().orElse(0);if(terminal>0)log.error("Could not grade {} predictions for {} at {}; {} became UNGRADABLE after {} attempts: {}",entry.getValue().size(),key.pair(),key.target(),terminal,MAX_GRADING_ATTEMPTS,lookup.error());else log.warn("Could not grade {} predictions for {} at {} (attempt {}/{}): {}",entry.getValue().size(),key.pair(),key.target(),attempt,MAX_GRADING_ATTEMPTS,lookup.error());}return count;}
    private PriceLookup lookup(TargetKey key){try{return new PriceLookup(market.priceAt(key.pair(),key.target()),null);}catch(Exception e){return new PriceLookup(null,e.getMessage());}}
    private record TargetKey(String pair,Instant target){}
    private record PriceLookup(BinanceMarketDataClient.TimedPrice price,String error){}
    public record RunResult(long runId,int predictionsCreated,List<String> errors){}
}
