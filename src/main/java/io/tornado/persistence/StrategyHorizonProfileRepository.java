package io.tornado.persistence;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface StrategyHorizonProfileRepository extends JpaRepository<StrategyHorizonProfile,Long>{
 @EntityGraph(attributePaths="coin") List<StrategyHorizonProfile>findByActiveTrueOrderByPredictionHorizonSecondsAscStrategyCodeAsc();
 @EntityGraph(attributePaths="coin") List<StrategyHorizonProfile>findAllByOrderBySelectedAtDesc();
 Optional<StrategyHorizonProfile>findByActiveKey(String activeKey);
 @Query("select coalesce(max(p.profileVersion),0) from StrategyHorizonProfile p where p.profileScope=:scope and ((:coinId is null and p.coin is null) or p.coin.id=:coinId) and p.strategyCode=:code and p.strategyVersion=:strategyVersion and p.predictionHorizonSeconds=:horizon")int maximumVersion(@Param("scope")ProfileScope scope,@Param("coinId")Long coinId,@Param("code")String code,@Param("strategyVersion")int strategyVersion,@Param("horizon")long horizon);
}
