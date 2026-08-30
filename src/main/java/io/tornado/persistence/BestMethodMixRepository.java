package io.tornado.persistence;
import org.springframework.data.jpa.repository.*;import org.springframework.data.repository.query.Param;import java.util.*;
public interface BestMethodMixRepository extends JpaRepository<BestMethodMix,Long>{
 @EntityGraph(attributePaths="coin")Optional<BestMethodMix>findOneByCoinIdAndHorizonSecondsAndSignalVersion(long coinId,long horizon,int signalVersion);
 @EntityGraph(attributePaths="coin")List<BestMethodMix>findBySignalVersionOrderByCoinSymbolAscHorizonSecondsAscMixSizeAscRankAsc(int signalVersion);
 @Modifying @Query("delete from BestMethodMix b where b.coin.id=:coinId and b.horizonSeconds=:horizon and b.signalVersion=:signalVersion")void deleteSlice(@Param("coinId")long coinId,@Param("horizon")long horizon,@Param("signalVersion")int signalVersion);
}
