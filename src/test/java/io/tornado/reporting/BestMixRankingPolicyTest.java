package io.tornado.reporting;

import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BestMixRankingPolicyTest {
    @Test void targetComparisonIgnoresBigDecimalScale() {
        Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);
        BestMethodMix mix=new BestMethodMix(coin,900,2,1,List.of("A","B"),List.of(1,1),List.of("A","B"),10,5,5,.5,1,new BigDecimal("0.3000"));
        assertThat(new BestMixRankingPolicy().isCurrent(mix,TpSlLevels.defaults())).isTrue();
    }

    @Test void stableLiveIdentityIgnoresStrategyOrderingAndTpDatabaseRow() {
        Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);BestMixRankingPolicy policy=new BestMixRankingPolicy();
        BestMethodMix tp1=new BestMethodMix(coin,900,2,1,List.of("A","B"),List.of(1,2),List.of("A","B"),10,5,5,.5,1,new BigDecimal(".3"));
        BestMethodMix tp2=new BestMethodMix(coin,900,2,2,List.of("B","A"),List.of(2,1),List.of("B","A"),10,5,5,.5,2,new BigDecimal(".5"));
        assertThat(policy.liveMixKey(tp1)).isEqualTo(policy.liveMixKey(tp2));
    }
}
