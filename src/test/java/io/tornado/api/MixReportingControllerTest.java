package io.tornado.api;

import io.tornado.persistence.*;
import io.tornado.reporting.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MixReportingControllerTest {
    @Test void bestMixApiNeverReturnsStaleTargetPercent() {
        var mixes=mock(BestMethodMixRepository.class);var settings=mock(AppSettingsRepository.class);Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);
        BestMethodMix current=mix(coin,new BigDecimal(".5000"));BestMethodMix stale=mix(coin,new BigDecimal(".80"));
        when(mixes.findBySignalVersionAndTpLevelOrderByCoinSymbolAscHorizonSecondsAscMixSizeAscRankAsc(3,2)).thenReturn(List.of(current,stale));when(settings.findById(1)).thenReturn(Optional.of(new AppSettings(900,900)));
        var controller=new MixReportingController(mixes,mock(BestMixService.class),mock(MixTradeSimulationService.class),settings,new BestMixRankingPolicy());
        assertThat(controller.best(null,null,2)).singleElement().satisfies(row->assertThat(row.targetPercent()).isEqualByComparingTo(".50"));
    }
    private BestMethodMix mix(Coin coin,BigDecimal target){BestMethodMix mix=new BestMethodMix(coin,900,2,1,List.of("A","B"),List.of(1,1),List.of("A","B"),10,5,5,.5,2,target);ReflectionTestUtils.setField(mix,"id",target.movePointRight(2).longValue());return mix;}
}
