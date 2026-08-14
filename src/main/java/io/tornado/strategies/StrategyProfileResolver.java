package io.tornado.strategies;
import io.tornado.persistence.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
@Service public class StrategyProfileResolver{
 private final StrategyHorizonProfileRepository profiles;public StrategyProfileResolver(StrategyHorizonProfileRepository profiles){this.profiles=profiles;}
 public Map<Long,List<StrategyHorizonProfile>>resolve(Collection<Coin>coins){return resolve(coins,profiles.findByActiveTrueOrderByPredictionHorizonSecondsAscStrategyCodeAsc());}
 Map<Long,List<StrategyHorizonProfile>>resolve(Collection<Coin>coins,List<StrategyHorizonProfile>active){Map<String,StrategyHorizonProfile>global=new HashMap<>(),specific=new HashMap<>();for(var p:active){String slice=p.getStrategyCode()+":"+p.getStrategyVersion()+":"+p.getPredictionHorizonSeconds();if(p.getProfileScope()==ProfileScope.GLOBAL)global.put(slice,p);else specific.put(p.getCoinId()+":"+slice,p);}Map<Long,List<StrategyHorizonProfile>>result=new HashMap<>();for(Coin coin:coins){List<StrategyHorizonProfile>selected=new ArrayList<>();for(var strategy:StrategyDefinition.values())for(long horizon:StrategyProfilePolicy.HORIZONS){String slice=strategy.code()+":"+strategy.version()+":"+horizon;StrategyHorizonProfile profile=specific.get(coin.getId()+":"+slice);if(profile==null)profile=global.get(slice);if(profile==null)profile=StrategyHorizonProfile.fallback(strategy.code(),strategy.version(),horizon,StrategyProfilePolicy.fallback(horizon),strategy.defaultParameterKey(),Instant.now());selected.add(profile);}result.put(coin.getId(),selected);}return result;}
}
