package io.tornado.reporting;

import io.tornado.persistence.Direction;
import io.tornado.persistence.PredictionRepository.MixSourceRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.LongConsumer;

/** Shared exact-strategy statistics engine used by reports and live Best Mix ranking. */
@Component
public class MixStatisticsCalculator {
    public List<Statistics> calculate(List<? extends MixSourceRow> rows, BigDecimal targetPercent,
                                      Collection<Integer> requestedSizes) {
        Objects.requireNonNull(targetPercent, "targetPercent");
        List<Integer> sizes=requestedSizes.stream().distinct().sorted().toList();
        if(sizes.stream().anyMatch(size->size<1||size>8))throw new IllegalArgumentException("mix size must be between 1 and 8");

        Map<String,Identity> identitiesByKey=new TreeMap<>();
        for(MixSourceRow row:rows){
            Identity candidate=new Identity(row.getStrategyCode(),row.getStrategyVersion(),row.getMethodName());
            identitiesByKey.merge(candidate.key(),candidate,(left,right)->left.name().compareTo(right.name())<=0?left:right);
        }
        List<Identity> identities=List.copyOf(identitiesByKey.values());
        if(identities.size()>63)throw new IllegalStateException("at most 63 strategy versions are supported");
        Map<String,Integer> indexes=new HashMap<>();for(int i=0;i<identities.size();i++)indexes.put(identities.get(i).key(),i);

        Map<GroupKey,Group> groups=new HashMap<>();
        for(MixSourceRow row:rows){
            if(row.getRunId()==null||row.getPriceAtGrading()==null)continue;
            Integer index=indexes.get(Identity.key(row.getStrategyCode(),row.getStrategyVersion()));
            if(index==null)continue;
            groups.computeIfAbsent(new GroupKey(row.getRunId(),row.getCoinId(),row.getHorizonSeconds()),ignored->new Group(row))
                    .put(index,row.getPredictedDirection());
        }

        Map<Integer,Map<Long,Score>> scores=new LinkedHashMap<>();for(int size:sizes)scores.put(size,new HashMap<>());
        BigDecimal target=targetPercent.movePointLeft(2);
        for(Group group:groups.values())for(int size:sizes){
            if(Long.bitCount(group.methods)<size)continue;
            combinations(group.methods,size,0,mask->score(group,size,mask,target,scores.get(size)));
        }

        List<Statistics> result=new ArrayList<>();
        scores.forEach((size,byMask)->byMask.forEach((mask,score)->{
            List<Identity> selected=decode(mask,identities);
            result.add(new Statistics(size,selected.stream().map(Identity::code).toList(),
                    selected.stream().map(Identity::version).toList(),selected.stream().map(Identity::name).toList(),
                    score.totalPredictions,score.sameDirectionPredictions,score.sameDirectionTargetHits,
                    score.samples,score.targetHits,score.directionalCorrect,wilson(score.targetHits,score.samples)));
        }));
        return List.copyOf(result);
    }

    private void score(Group group,int size,long mask,BigDecimal target,Map<Long,Score> scores){
        int ups=Long.bitCount(mask&group.ups),downs=size-ups;Score score=scores.computeIfAbsent(mask,ignored->new Score());score.totalPredictions++;
        boolean sameDirection=ups==0||downs==0,predictedUp=ups>downs,targetHit=group.target(predictedUp,target);
        if(sameDirection){score.sameDirectionPredictions++;if(targetHit)score.sameDirectionTargetHits++;}
        if(ups==downs)return;
        score.samples++;if(targetHit)score.targetHits++;if(group.direction(predictedUp))score.directionalCorrect++;
    }

    private static void combinations(long remaining,int needed,long selected,LongConsumer consumer){if(needed==0){consumer.accept(selected);return;}while(Long.bitCount(remaining)>=needed){long bit=Long.lowestOneBit(remaining);remaining^=bit;combinations(remaining,needed-1,selected|bit,consumer);}}
    private static List<Identity> decode(long mask,List<Identity> identities){List<Identity> result=new ArrayList<>();while(mask!=0){long bit=Long.lowestOneBit(mask);result.add(identities.get(Long.numberOfTrailingZeros(bit)));mask^=bit;}return result;}
    private static double wilson(long hits,long samples){if(samples==0)return 0;double z=1.96,p=(double)hits/samples,z2=z*z;return(p+z2/(2*samples)-z*Math.sqrt((p*(1-p)+z2/(4*samples))/samples))/(1+z2/samples);}

    private record Identity(String code,int version,String name){String key(){return key(code,version);}static String key(String code,int version){return code+"\u0000"+version;}}
    private record GroupKey(long run,long coin,long horizon){}
    private static class Score{long totalPredictions,sameDirectionPredictions,sameDirectionTargetHits,samples,targetHits,directionalCorrect;}
    private static class Group{long methods,ups;final BigDecimal upwardReturn;Group(MixSourceRow row){upwardReturn=row.getPriceAtGrading().subtract(row.getPriceAtPrediction()).divide(row.getPriceAtPrediction(),12,RoundingMode.HALF_UP);}void put(int index,Direction direction){long bit=1L<<index;methods|=bit;if(direction==Direction.UP)ups|=bit;else ups&=~bit;}boolean target(boolean up,BigDecimal target){return(up?upwardReturn:upwardReturn.negate()).compareTo(target)>=0;}boolean direction(boolean up){return up?upwardReturn.signum()>0:upwardReturn.signum()<0;}}

    public record Statistics(int size,List<String> strategyCodes,List<Integer> strategyVersions,List<String> methodNames,
                             long totalPredictions,long sameDirectionPredictions,long sameDirectionTargetHits,
                             long samples,long targetHits,long directionalCorrect,double wilson){
        public double targetHitRate(){return samples==0?0:targetHits*100.0/samples;}
        public double directionalAccuracy(){return samples==0?0:directionalCorrect*100.0/samples;}
        public String strategyIdentity(){List<String> values=new ArrayList<>();for(int i=0;i<strategyCodes.size();i++)values.add(strategyCodes.get(i)+"@"+strategyVersions.get(i));values.sort(String::compareTo);return String.join(",",values);}
    }
}
