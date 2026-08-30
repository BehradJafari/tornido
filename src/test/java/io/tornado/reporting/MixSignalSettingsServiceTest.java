package io.tornado.reporting;

import io.tornado.persistence.AppSettings;
import io.tornado.persistence.AppSettingsRepository;
import io.tornado.persistence.TpSlLevels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MixSignalSettingsServiceTest {
    @Test void changingOnlySlValuesDoesNotTriggerBestMixRebuildEvent() {
        Fixture f = new Fixture();
        f.service.update(30, levels(".30",".50","1.00",".20",".40",".90"), true);
        verify(f.events, never()).publishEvent(any());
    }

    @ParameterizedTest @ValueSource(ints={1})
    void changingRankingTp1TriggersRebuildEvent(int level) {
        Fixture f = new Fixture();
        String[] tp={".30",".50","1.00"}; tp[level-1]=switch(level){case 1->".35";case 2->".60";default->"1.20";};
        f.service.update(30, levels(tp[0],tp[1],tp[2],".30",".50","1.00"), true);
        verify(f.events).publishEvent(any(BestMixTargetsChanged.class));
    }

    @Test void changingOnlyResearchTp2AndTp3DoesNotRebuildLiveTp1Ranking(){Fixture f=new Fixture();f.service.update(30,levels(".30",".60","1.20",".30",".50","1.00"),true);verify(f.events,never()).publishEvent(any());}

    @Test void changingMinimumWinRateTriggersRebuildEvent(){Fixture f=new Fixture();f.service.update(30,TpSlLevels.defaults(),new BigDecimal("87"),true);verify(f.events).publishEvent(any(BestMixTargetsChanged.class));}

    @Test void changingMultipleTpValuesPublishesOneRebuildEvent() {
        Fixture f = new Fixture();
        f.service.update(30, levels(".40",".80","1.50",".30",".50","1.00"), true);
        verify(f.events).publishEvent(any(BestMixTargetsChanged.class));
    }

    @Test void scaleOnlyDifferenceDoesNotTriggerRebuild() {
        Fixture f = new Fixture();
        f.service.update(30, levels(".3000",".5000","1.0000",".30",".50","1.00"), true);
        verify(f.events, never()).publishEvent(any());
    }

    @Test void persistenceFailureDoesNotPublishRebuildEvent() {
        Fixture f = new Fixture(); when(f.settings.saveAndFlush(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> f.service.update(30,levels(".40",".80","1.50",".30",".50","1.00"),true)).isInstanceOf(IllegalStateException.class);
        verify(f.events, never()).publishEvent(any());
    }

    private static TpSlLevels levels(String tp1,String tp2,String tp3,String sl1,String sl2,String sl3) { return new TpSlLevels(new BigDecimal(tp1),new BigDecimal(tp2),new BigDecimal(tp3),new BigDecimal(sl1),new BigDecimal(sl2),new BigDecimal(sl3)); }

    static class Fixture {
        final AppSettingsRepository settings=mock(AppSettingsRepository.class); final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
        final AppSettings configuration=new AppSettings(900,900); final MixSignalSettingsService service;
        Fixture(){when(settings.findById(1)).thenReturn(Optional.of(configuration));when(settings.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));service=new MixSignalSettingsService(settings,new BestMixRankingPolicy(),events);}
    }
}
