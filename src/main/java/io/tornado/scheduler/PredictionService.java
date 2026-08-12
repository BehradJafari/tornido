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
    private final CoinRepository coins; private final PredictionRepository predictions; private final AppSettingsRepository settings; private final AnalysisRunRepository runs; private final BinanceMarketDataClient market;
    public PredictionService(CoinRepository c,PredictionRepository p,AppSettingsRepository s,AnalysisRunRepository r,BinanceMarketDataClient m){coins=c;predictions=p;settings=s;runs=r;market=m;}
    public RunResult snapshot(){return snapshot("Scheduled analysis "+Instant.now());}
    public RunResult snapshot(String name){AnalysisRun run=runs.save(new AnalysisRun(name,Duration.ZERO));int saved=0;List<String> errors=new ArrayList<>();
        for(Coin coin:coins.findAllByActiveTrueOrderBySymbol())try{var series=market.candles(coin.getPair());BigDecimal price=market.price(coin.getPair());Instant now=Instant.now();for(var def:StrategyDefinition.values()){var signal=def.evaluate(series);if(signal.direction()==Direction.NEUTRAL)continue;for(Duration horizon:AUTOMATIC_HORIZONS){predictions.save(new Prediction(run,coin,def.label(),now,signal.direction(),price,horizon));saved++;}}}catch(Exception e){log.error("Snapshot failed for {}",coin.getPair(),e);errors.add(coin.getPair()+": "+e.getMessage());}
        run.complete(errors.size(),saved);runs.save(run);return new RunResult(run.getId(),saved,errors);
    }
    @Transactional public int gradeDue(){int count=0;Instant now=Instant.now();Map<TargetKey,BigDecimal> prices=new HashMap<>();for(Prediction p:predictions.findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome.PENDING,2)){Instant target=p.getPredictedAt().plusSeconds(p.getHorizonSeconds());if(target.isAfter(now))continue;try{TargetKey key=new TargetKey(p.getCoin().getPair(),target);BigDecimal price=prices.computeIfAbsent(key,k->market.priceAt(k.pair(),k.target()));p.grade(price,now,MINIMUM_CORRECT_MOVE);count++;}catch(Exception e){log.warn("Could not grade prediction {}: {}",p.getId(),e.getMessage());}}return count;}
    private record TargetKey(String pair,Instant target){}
    public record RunResult(long runId,int predictionsCreated,List<String> errors){}
}
