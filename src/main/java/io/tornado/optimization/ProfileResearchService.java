package io.tornado.optimization;

import io.tornado.api.ReportService;
import io.tornado.datafetch.*;
import io.tornado.persistence.*;
import io.tornado.strategies.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class ProfileResearchService {
    private static final Logger log=LoggerFactory.getLogger(ProfileResearchService.class);
    private static final int WARMUP_BARS=80;
    private final BinanceMarketDataClient market;
    private final CoinRepository coins;
    private final AppSettingsRepository settings;
    private final ProfileSelectionService selection;
    private final WalkForwardProfileSelector selector=new WalkForwardProfileSelector();

    public ProfileResearchService(BinanceMarketDataClient market,CoinRepository coins,AppSettingsRepository settings,ProfileSelectionService selection){this.market=market;this.coins=coins;this.settings=settings;this.selection=selection;}

    public ResearchResult research(ProfileScope scope,String coinSymbol,String strategyCode,long horizon,Instant from,Instant to){
        ReportService.requireSupportedHorizon(horizon);StrategyDefinition strategy=StrategyDefinition.valueOf(strategyCode);
        List<Coin>universe;if(scope==ProfileScope.COIN_SPECIFIC){if(coinSymbol==null||coinSymbol.isBlank())throw new IllegalArgumentException("coin is required for a coin-specific profile");universe=List.of(coins.findBySymbolIgnoreCase(coinSymbol).orElseThrow());}else universe=coins.findAllByActiveTrueOrderBySymbol();
        if(universe.isEmpty())throw new IllegalStateException("No active coins are available for research");if(from==null)from=to.minus(horizon<=3600?Duration.ofDays(90):Duration.ofDays(365));if(!to.isAfter(from))throw new IllegalArgumentException("research end must follow start");
        AppSettings configuration=settings.findById(1).orElseThrow();Duration snapshotInterval=Duration.ofSeconds(configuration.getSnapshotIntervalSeconds());SortedSet<Instant>priceTimes=requestedPriceTimes(from,to,horizon,snapshotInterval);
        Map<WalkForwardProfileSelector.Configuration,List<WalkForwardProfileSelector.Observation>>data=new LinkedHashMap<>();List<String>errors=new ArrayList<>();
        for(Coin coin:universe){
            HistoricalTradeTimeline executionTimeline;
            try{executionTimeline=market.historicalTradeTimeline(coin.getPair(),priceTimes);}catch(Exception e){errors.add(coin.getSymbol()+"/aggregate-trades: "+e.getMessage());log.warn("Profile research skipped {} aggregate-trade timeline: {}",coin.getPair(),e.getMessage());continue;}
            for(String timeframe:StrategyProfilePolicy.candidateTimeframes(horizon)){
                Duration warmup=StrategyProfilePolicy.duration(timeframe).multipliedBy(WARMUP_BARS);
                try{BarSeries series=market.historicalCandles(coin.getPair(),timeframe,from.minus(warmup),to);for(String parameters:strategy.parameterKeys()){var key=new WalkForwardProfileSelector.Configuration(timeframe,parameters);data.computeIfAbsent(key,x->new ArrayList<>()).addAll(observations(coin,strategy,parameters,horizon,from,to,snapshotInterval,series,executionTimeline));}}
                catch(Exception e){errors.add(coin.getSymbol()+"/"+timeframe+": "+e.getMessage());log.warn("Profile research skipped {} {}: {}",coin.getPair(),timeframe,e.getMessage());}
            }
        }
        int minimum=scope==ProfileScope.GLOBAL?configuration.getMinimumConfigurationSamples():configuration.getCoinProfileMinimumSamples();List<WalkForwardProfileSelector.CandidateEvaluation>ranked=selector.rank(data,minimum,configuration.getProfileResearchRoundTripCostPercent().doubleValue());var winner=ranked.stream().filter(WalkForwardProfileSelector.CandidateEvaluation::eligible).findFirst().orElse(null);
        ProfileSelectionService.Selection applied=winner==null?new ProfileSelectionService.Selection(false,"No candidate met chronological sample requirements",null):selection.apply(winner,scope,scope==ProfileScope.COIN_SPECIFIC?universe.getFirst():null,strategy.code(),strategy.version(),horizon);
        return new ResearchResult(scope,scope==ProfileScope.COIN_SPECIFIC?universe.getFirst().getSymbol():null,strategy.code(),horizon,from,to,ranked,applied,errors);
    }

    SortedSet<Instant>requestedPriceTimes(Instant from,Instant to,long horizon,Duration snapshotInterval){if(snapshotInterval.isZero()||snapshotInterval.isNegative())throw new IllegalArgumentException("snapshot interval must be positive");SortedSet<Instant>times=new TreeSet<>();for(Instant executionAt=from;!executionAt.plusSeconds(horizon).isAfter(to);executionAt=executionAt.plus(snapshotInterval)){times.add(executionAt);times.add(executionAt.plusSeconds(horizon));}return times;}

    List<WalkForwardProfileSelector.Observation>observations(Coin coin,StrategyDefinition strategy,String parameters,long horizon,Instant from,Instant to,Duration snapshotInterval,BarSeries analysis,HistoricalTradeTimeline timeline){
        List<WalkForwardProfileSelector.Observation>out=new ArrayList<>();int analysisIndex=analysis.getBeginIndex()-1;
        for(Instant executionAt=from;!executionAt.plusSeconds(horizon).isAfter(to);executionAt=executionAt.plus(snapshotInterval)){
            while(analysisIndex+1<=analysis.getEndIndex()&&!analysis.getBar(analysisIndex+1).getEndTime().isAfter(executionAt))analysisIndex++;
            if(analysisIndex<analysis.getBeginIndex()+WARMUP_BARS)continue;
            Instant signalAt=analysis.getBar(analysisIndex).getEndTime(),targetAt=executionAt.plusSeconds(horizon);var signal=strategy.evaluateAt(analysis,parameters,analysisIndex);if(signal.direction()==Direction.NEUTRAL)continue;
            var entry=timeline.tradeAtOrAfter(executionAt);if(entry.isEmpty())continue;var exit=timeline.tradeAtOrAfter(targetAt);if(exit.isEmpty())continue;
            long entryDelay=Duration.between(executionAt,entry.get().observedAt()).toMillis(),exitDelay=Duration.between(targetAt,exit.get().observedAt()).toMillis();
            out.add(new WalkForwardProfileSelector.Observation(coin.getId(),signalAt,executionAt,targetAt,signal.direction(),entry.get().price(),exit.get().price(),entry.get().observedAt(),exit.get().observedAt(),entryDelay,exitDelay));
        }
        return out;
    }

    public record ResearchResult(ProfileScope scope,String coin,String strategyCode,long horizon,Instant from,Instant to,List<WalkForwardProfileSelector.CandidateEvaluation>candidates,ProfileSelectionService.Selection selection,List<String>errors){}
}
