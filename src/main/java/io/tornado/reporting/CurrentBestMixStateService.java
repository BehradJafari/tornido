package io.tornado.reporting;

import io.tornado.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CurrentBestMixStateService {
    private final PredictionRepository predictions;private final AnalysisRunRepository runs;private final ActiveSignalLockRepository locks;
    public CurrentBestMixStateService(PredictionRepository predictions,AnalysisRunRepository runs,ActiveSignalLockRepository locks){this.predictions=predictions;this.runs=runs;this.locks=locks;}

    @Transactional(readOnly=true)
    public Map<Long,State> states(Collection<BestMethodMix> mixes){
        if(mixes.isEmpty())return Map.of();Long runId=runs.findTopByOrderByCreatedAtDesc().map(AnalysisRun::getId).orElse(null);
        Set<String> openScopes=new HashSet<>();for(ActiveSignalLock lock:locks.findByStatusOrderByOpenedAtDesc(ActiveSignalLock.Status.OPEN))openScopes.add(scope(lock.getCoin().getId(),lock.getHorizonSeconds()));
        Map<SliceKey,Map<String,Direction>> votesByHorizonCoin=new HashMap<>();
        if(runId!=null)for(long horizon:mixes.stream().map(BestMethodMix::getHorizonSeconds).distinct().toList())for(PredictionRepository.ReportRow row:predictions.findLiveReportRows(runId,horizon,Prediction.CURRENT_SIGNAL_VERSION))votesByHorizonCoin.computeIfAbsent(new SliceKey(row.getCoinId(),horizon),ignored->new HashMap<>()).put(strategy(row.getStrategyCode(),row.getStrategyVersion()),row.getPredictedDirection());
        Map<Long,State> result=new HashMap<>();for(BestMethodMix mix:mixes){List<Direction> votes=new ArrayList<>();Map<String,Direction> current=votesByHorizonCoin.getOrDefault(new SliceKey(mix.getCoin().getId(),mix.getHorizonSeconds()),Map.of());for(int i=0;i<mix.getStrategyCodes().size();i++){Direction vote=current.get(strategy(mix.getStrategyCodes().get(i),mix.getStrategyVersions().get(i)));if(vote!=null)votes.add(vote);}MixConsensus.Result consensus=MixConsensus.decide(mix.getMixSize(),votes);result.put(mix.getId(),new State(consensus.decisive()?(consensus.direction()==Direction.UP?"LONG":"SHORT"):"NO_SIGNAL",consensus.agreement(),consensus.required(),openScopes.contains(scope(mix.getCoin().getId(),mix.getHorizonSeconds()))?"OPEN":"NONE"));}return result;
    }
    private String scope(long coin,long horizon){return coin+":"+horizon;}private String strategy(String code,int version){return code+"\u0000"+version;}
    private record SliceKey(long coinId,long horizonSeconds){}
    public record State(String consensus,int agreement,int requiredVotes,String activeLockStatus){public static State empty(int mixSize){return new State("NO_SIGNAL",0,mixSize/2+1,"NONE");}}
}
