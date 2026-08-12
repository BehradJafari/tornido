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
    @Transactional public int gradeDue(){int count=0;Instant now=Instant.now();Map<TargetKey,BinanceMarketDataClient.TimedPrice> prices=new HashMap<>();for(Prediction p:predictions.findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome.PENDING,2)){Instant target=p.getPredictedAt().plusSeconds(p.getHorizonSeconds());if(target.isAfter(now))continue;try{TargetKey key=new TargetKey(p.getCoin().getPair(),target);var price=prices.computeIfAbsent(key,k->market.priceAt(k.pair(),k.target()));p.grade(price.price(),price.observedAt(),now,MINIMUM_CORRECT_MOVE);count++;}catch(Exception e){p.recordGradingError(e.getMessage());if(p.getGradingAttempts()>=MAX_GRADING_ATTEMPTS){p.markUngradable(now);log.error("Prediction {} is permanently ungradable after {} attempts: {}",p.getId(),p.getGradingAttempts(),e.getMessage());}else log.warn("Could not grade prediction {} (attempt {}/{}): {}",p.getId(),p.getGradingAttempts(),MAX_GRADING_ATTEMPTS,e.getMessage());}}return count;}
    private record TargetKey(String pair,Instant target){}
    public record RunResult(long runId,int predictionsCreated,List<String> errors){}
}
