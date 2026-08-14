package io.tornado.reporting;

import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BestMixRankingMaintenanceTest {
    @Test void invalidatesOnlyRowsWhoseStoredTargetNoLongerMatchesCurrentSettings() {
        var mixes=mock(BestMethodMixRepository.class);var settings=mock(AppSettingsRepository.class);Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);
        AppSettings configuration=new AppSettings(900,900);when(settings.findById(1)).thenReturn(Optional.of(configuration));
        BestMethodMix current=new BestMethodMix(coin,900,2,1,List.of("A","B"),List.of(1,1),List.of("A","B"),10,5,5,.5,2,new BigDecimal("0.5000"));
        BestMethodMix stale1=new BestMethodMix(coin,900,2,1,List.of("C","D"),List.of(1,1),List.of("C","D"),10,5,5,.5,1,new BigDecimal("0.40"));
        BestMethodMix stale3=new BestMethodMix(coin,900,2,1,List.of("E","F"),List.of(1,1),List.of("E","F"),10,5,5,.5,3,new BigDecimal("1.50"));
        when(mixes.findBySignalVersionOrderByCoinSymbolAscHorizonSecondsAscMixSizeAscRankAsc(3)).thenReturn(List.of(current,stale1,stale3));
        int removed=new BestMixRankingMaintenance(mixes,settings,new BestMixRankingPolicy()).invalidateStale();
        @SuppressWarnings("unchecked") ArgumentCaptor<Iterable<BestMethodMix>> deleted=ArgumentCaptor.forClass(Iterable.class);verify(mixes).deleteAllInBatch(deleted.capture());
        List<BestMethodMix> deletedRows=new ArrayList<>();deleted.getValue().forEach(deletedRows::add);assertThat(deletedRows).containsExactly(stale1,stale3);assertThat(removed).isEqualTo(2);
    }

    @Test void rebuildFailureIsContainedAfterInvalidationAttempt() {
        var maintenance=mock(BestMixRankingMaintenance.class);var service=mock(BestMixService.class);doThrow(new IllegalStateException("rebuild failed")).when(service).rebuildAll();
        new BestMixTargetsChangedListener(maintenance,service).targetsChanged(new BestMixTargetsChanged(TpSlLevels.defaults(),new TpSlLevels(new BigDecimal(".4"),new BigDecimal(".8"),new BigDecimal("1.5"),new BigDecimal(".3"),new BigDecimal(".5"),BigDecimal.ONE)));
        verify(maintenance).invalidateStale();verify(service).rebuildAll();
    }
}
