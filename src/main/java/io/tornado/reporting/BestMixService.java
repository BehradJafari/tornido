package io.tornado.reporting;

import io.tornado.api.ReportService;
import io.tornado.persistence.*;
import io.tornado.persistence.PredictionRepository.ReportRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class BestMixService {
    private static final Logger log=LoggerFactory.getLogger(BestMixService.class);
    private static final List<Integer> LIVE_MIX_SIZES=List.of(2,3,4,5,6,7,8);
    static final Comparator<Candidate> STRONGEST_FIRST=Comparator
            .comparingDouble(Candidate::wilson).reversed()
            .thenComparing(Comparator.comparingDouble(Candidate::targetHitRate).reversed())
            .thenComparing(Comparator.comparingLong(Candidate::samples).reversed())
            .thenComparing(Comparator.comparingDouble(Candidate::directionalAccuracy).reversed())
            .thenComparingInt(Candidate::size)
            .thenComparing(Candidate::strategyIdentity);

    private final PredictionRepository predictions;
    private final BestMethodMixRepository mixes;
    private final CoinRepository coins;
    private final AppSettingsRepository settings;
    private final MixStatisticsCalculator calculator;

    @Autowired
    public BestMixService(PredictionRepository predictions,BestMethodMixRepository mixes,CoinRepository coins,
                          AppSettingsRepository settings,MixStatisticsCalculator calculator){this.predictions=predictions;this.mixes=mixes;this.coins=coins;this.settings=settings;this.calculator=calculator;}
    public BestMixService(PredictionRepository predictions,BestMethodMixRepository mixes,CoinRepository coins,
                          AppSettingsRepository settings){this(predictions,mixes,coins,settings,new MixStatisticsCalculator());}

    /** Replaces a live slice with its single TP1-ranked winner, or leaves it empty. */
    @Transactional
    public synchronized void refresh(long coinId,long horizon){
        ReportService.requireSupportedHorizon(horizon);
        mixes.deleteSlice(coinId,horizon,Prediction.CURRENT_SIGNAL_VERSION);mixes.flush();
        if(!PredictionServiceHorizons.supportsLiveSignal(horizon))return;

        Coin coin=coins.findById(coinId).orElseThrow();AppSettings configuration=settings.findById(1).orElseThrow();
        List<ReportRow> rows=predictions.findGradedReportRows(coinId,horizon);
        List<Candidate> all=calculator.calculate(rows,configuration.getTakeProfit1Percent(),LIVE_MIX_SIZES).stream().map(Candidate::from).toList();
        long eligibleBySamples=all.stream().filter(x->x.samples()>=configuration.getMinimumMixSimulationTrades()).count();
        List<Candidate> eligible=all.stream().filter(x->x.samples()>=configuration.getMinimumMixSimulationTrades())
                .filter(x->BigDecimal.valueOf(x.targetHitRate()).compareTo(configuration.getMinimumNotificationWinRatePercent())>=0)
                .sorted(STRONGEST_FIRST).toList();

        if(eligible.isEmpty()){
            log.info("BEST_MIX_NONE coin={} horizon={} candidateCount={} eligibleBySamples={} eligibleByWinRate=0 reason=NO_CANDIDATE_ABOVE_THRESHOLDS",
                    coin.getSymbol(),horizon,all.size(),eligibleBySamples);return;
        }
        Candidate winner=eligible.getFirst();
        BestMethodMix saved=mixes.save(new BestMethodMix(coin,horizon,winner.size(),1,winner.codes(),winner.versions(),winner.names(),
                winner.samples(),winner.hits(),winner.directional(),winner.wilson(),1,configuration.getTakeProfit1Percent()));
        log.info("BEST_MIX_RECALCULATE coin={} horizon={} candidateCount={} eligibleBySamples={} eligibleByWinRate={} winnerMethods={} winnerSamples={} winnerWinRate={} winnerWilson={} mixId={}",
                coin.getSymbol(),horizon,all.size(),eligibleBySamples,eligible.size(),winner.strategyIdentity(),winner.samples(),winner.targetHitRate(),winner.wilson()*100,saved.getId());
    }

    @Transactional public synchronized void refresh(Collection<Slice> slices){for(Slice slice:slices)if(PredictionServiceHorizons.supportsLiveSignal(slice.horizon()))refresh(slice.coinId(),slice.horizon());}
    @Transactional public synchronized void rebuildAll(){for(Coin coin:coins.findAllByActiveTrueOrderBySymbol())for(long horizon:PredictionServiceHorizons.LIVE_SIGNAL_HORIZONS_SORTED)refresh(coin.getId(),horizon);}

    List<Candidate> calculate(List<ReportRow> rows,int minimum){return calculate(rows,minimum,BigDecimal.ZERO,TpSlLevels.defaults().tp1());}
    List<Candidate> calculate(List<ReportRow> rows,int minimum,BigDecimal minimumWinRate,BigDecimal targetPercent){
        return calculator.calculate(rows,targetPercent,LIVE_MIX_SIZES).stream().map(Candidate::from)
                .filter(x->x.samples()>=minimum).filter(x->BigDecimal.valueOf(x.targetHitRate()).compareTo(minimumWinRate)>=0)
                .sorted(STRONGEST_FIRST).toList();
    }

    public record Slice(long coinId,long horizon){}
    record Candidate(int size,List<String> codes,List<Integer> versions,List<String> names,long samples,long hits,long directional,double wilson){
        static Candidate from(MixStatisticsCalculator.Statistics value){return new Candidate(value.size(),value.strategyCodes(),value.strategyVersions(),value.methodNames(),value.samples(),value.targetHits(),value.directionalCorrect(),value.wilson());}
        double targetHitRate(){return samples==0?0:hits*100.0/samples;}double directionalAccuracy(){return samples==0?0:directional*100.0/samples;}
        String strategyIdentity(){List<String> identities=new ArrayList<>();for(int i=0;i<codes.size();i++)identities.add(codes.get(i)+"@"+versions.get(i));identities.sort(String::compareTo);return String.join(",",identities);}
    }
}
