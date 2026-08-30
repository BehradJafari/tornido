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
    @Test void apiReturnsOnlyCurrentEligibleTp1LiveWinner(){
        var mixes=mock(BestMethodMixRepository.class);var settings=mock(AppSettingsRepository.class);Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);
        AppSettings configuration=new AppSettings(900,900);configuration.updateMixSignals(50,configuration.getTpSlLevels(),new BigDecimal("87"),true);
        BestMethodMix current=mix(coin,3600,1,".30",100,90);BestMethodMix belowRate=mix(coin,14400,1,".30",100,70);BestMethodMix oldTp2=mix(coin,43200,2,".50",100,95);BestMethodMix shortHorizon=mix(coin,900,1,".30",100,95);
        when(mixes.findBySignalVersionOrderByCoinSymbolAscHorizonSecondsAscMixSizeAscRankAsc(3)).thenReturn(List.of(current,belowRate,oldTp2,shortHorizon));when(settings.findById(1)).thenReturn(Optional.of(configuration));
        var controller=new MixReportingController(mixes,mock(BestMixService.class),mock(MixTradeSimulationService.class),settings,new BestMixRankingPolicy());

        assertThat(controller.best(null,null)).singleElement().satisfies(row->{assertThat(row.id()).isEqualTo(current.getId());assertThat(row.tpLevel()).isEqualTo(1);assertThat(row.rank()).isEqualTo(1);});
    }
    private BestMethodMix mix(Coin coin,long horizon,int tp,String target,long samples,long hits){BestMethodMix mix=new BestMethodMix(coin,horizon,2,1,List.of("A","B"),List.of(1,1),List.of("A","B"),samples,hits,hits,.5,tp,new BigDecimal(target));ReflectionTestUtils.setField(mix,"id",horizon+tp);return mix;}
}
