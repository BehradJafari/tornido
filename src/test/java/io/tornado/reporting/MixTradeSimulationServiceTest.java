package io.tornado.reporting;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.notification.TelegramMessageFormatter;
import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MixTradeSimulationServiceTest {
    @Test void onlyTp1RankingCreatesSimulation() { assertOnlyTpCreates(1); }
    @Test void onlyTp2RankingCreatesSimulation() { assertOnlyTpCreates(2); }
    @Test void onlyTp3RankingCreatesSimulation() { assertOnlyTpCreates(3); }

    @Test void differentCurrentMixesAcrossAllTpLevelsCreateUniqueSimulations() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(1, 1, "A", "B", "C"), f.mix(2, 1, "D", "E", "F"), f.mix(3, 1, "G", "H", "I"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C","D","E","F","G","H","I"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations, times(3)).saveAndFlush(any());
    }

    @Test void sameCombinationAcrossTp1AndTp2CreatesOneSimulation() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(2, 1, "A", "B", "C"), f.mix(1, 3, "A", "B", "C"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> saved = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getBestMix().getTpLevel()).isEqualTo(1);
    }

    @Test void sameCombinationAcrossAllTpLevelsCreatesOneSimulationAndOneTelegramMessage() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(3, 1, "A", "B", "C"), f.mix(1, 2, "A", "B", "C"), f.mix(2, 1, "A", "B", "C"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations).saveAndFlush(any());
        verify(f.telegram).send(anyString());
    }

    @Test void oppositeConsensusDirectionUsesDifferentActiveIdentity() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(1, 1, "A", "B", "C"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        f.service.detect(f.coin, f.predictions(Direction.DOWN, "A","B","C"), Instant.EPOCH.plusSeconds(1), new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> saved = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(MixTradeSimulation::getDirection).containsExactly(Direction.UP, Direction.DOWN);
        assertThat(MixTradeSimulation.activeKey(f.coin, f.mix, Direction.UP)).isNotEqualTo(MixTradeSimulation.activeKey(f.coin, f.mix, Direction.DOWN));
    }

    @Test void staleTargetPercentRankingIsIgnored() {
        Fixture f = new Fixture();
        BestMethodMix stale = f.mix(2, 1, new BigDecimal("0.80"), "A", "B", "C");
        f.useMixes(stale);
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations, never()).saveAndFlush(any());
        verifyNoInteractions(f.telegram);
    }

    @Test void activeKeyDatabaseGuardStillSuppressesConcurrentDuplicate() {
        Fixture f = new Fixture();
        f.useMixes(f.mix);
        when(f.simulations.existsByActiveKey(anyString())).thenReturn(true);
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations, never()).saveAndFlush(any());
        verifyNoInteractions(f.telegram);
    }

    @Test void qualifyingConsensusPersistsConfiguredLadder() {
        Fixture f = new Fixture();
        f.useMixes(f.mix);
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> capture = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations).saveAndFlush(capture.capture());
        assertThat(capture.getValue().getTelegramMessageId()).isEqualTo(42);
        assertThat(capture.getValue().getTp3Price()).isEqualByComparingTo("101");
        verify(f.telegram).send(messageContaining("TP1","TP2","TP3","SL1","SL2","SL3"));
    }

    @Test void oneTickCrossingAllTargetsProducesOneTerminalEdit() {
        Fixture f = new Fixture(); MixTradeSimulation s = f.simulation(Direction.UP);
        when(f.simulations.lockOpenByPair("BTCUSDT")).thenReturn(List.of(s)); when(f.telegram.edit(eq(42L),anyString())).thenReturn(f.sent());
        f.service.observe("BTCUSDT",new BigDecimal("101.2"),Instant.EPOCH.plusSeconds(60));
        assertThat(s.getStatus()).isEqualTo(MixTradeSimulation.Status.TP3_HIT); assertThat(s.getTp1HitAt()).isEqualTo(s.getTp3HitAt());
        verify(f.telegram).edit(eq(42L),messageContaining("TP1 TOUCHED","TP2 TOUCHED","TP3 TOUCHED","TP3 HIT")); verify(f.telegram,never()).send(anyString());
    }

    @Test void stopTerminalEditFallsBackWithoutLosingState() {
        Fixture f = new Fixture(); MixTradeSimulation s = f.simulation(Direction.DOWN);
        when(f.simulations.lockOpenByPair("BTCUSDT")).thenReturn(List.of(s)); when(f.telegram.edit(eq(42L),anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.FAILED,null,"edit failed"));
        f.service.observe("BTCUSDT",new BigDecimal("101.2"),Instant.EPOCH.plusSeconds(60));
        assertThat(s.getStatus()).isEqualTo(MixTradeSimulation.Status.SL3_HIT); verify(f.telegram).edit(eq(42L),contains("SL3 HIT")); verify(f.telegram).send(contains("SL3 HIT"));
    }

    @Test void recoveryPreservesOrderedMixedTouchesAndCoalescesEdit() {
        Fixture f = new Fixture(); MixTradeSimulation s = f.simulation(Direction.UP);
        when(f.simulations.lockOpenByPair("BTCUSDT")).thenReturn(List.of(s));
        when(f.market.historicalTrades(eq("BTCUSDT"),eq(Instant.EPOCH),eq(Instant.EPOCH.plusSeconds(30)))).thenReturn(List.of(new BinanceMarketDataClient.AggregateTrade(1,new BigDecimal("99.4"),Instant.EPOCH.plusSeconds(10)),new BinanceMarketDataClient.AggregateTrade(2,new BigDecimal("100.6"),Instant.EPOCH.plusSeconds(20))));
        when(f.telegram.edit(eq(42L),anyString())).thenReturn(f.sent()); f.service.recover("BTCUSDT",Instant.EPOCH.plusSeconds(30));
        assertThat(s.getStatus()).isEqualTo(MixTradeSimulation.Status.OPEN); assertThat(s.getSl1HitAt()).isEqualTo(Instant.EPOCH.plusSeconds(10)); assertThat(s.getTp1HitAt()).isEqualTo(Instant.EPOCH.plusSeconds(20));
        verify(f.telegram).edit(eq(42L),messageContaining("SL1 TOUCHED","SL2 TOUCHED","TP1 TOUCHED","TP2 TOUCHED"));
    }

    private void assertOnlyTpCreates(int tpLevel) {
        Fixture f = new Fixture(); f.useMixes(f.mix(tpLevel, 1, "A", "B", "C"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations).saveAndFlush(any());
    }

    private String messageContaining(String... parts) { return argThat(message -> Arrays.stream(parts).allMatch(message::contains)); }

    static class Fixture {
        final Coin coin = new Coin("BTC","BTCUSDT"); final BestMethodMixRepository mixes = mock(BestMethodMixRepository.class);
        final MixTradeSimulationRepository simulations = mock(MixTradeSimulationRepository.class); final AppSettingsRepository settings = mock(AppSettingsRepository.class);
        final TelegramNotificationService telegram = mock(TelegramNotificationService.class); final BinanceMarketDataClient market = mock(BinanceMarketDataClient.class);
        final AppSettings configuration = new AppSettings(900,900); final BestMethodMix mix; final MixTradeSimulationService service; final AtomicLong ids = new AtomicLong(10);
        Fixture() {
            ReflectionTestUtils.setField(coin,"id",1L); configuration.updateTelegram(true); when(settings.findById(1)).thenReturn(Optional.of(configuration));
            mix = mix(1,1,"A","B","C"); when(simulations.existsByActiveKey(anyString())).thenReturn(false);
            when(simulations.saveAndFlush(any())).thenAnswer(invocation -> { MixTradeSimulation simulation=invocation.getArgument(0); ReflectionTestUtils.setField(simulation,"id",ids.incrementAndGet()); return simulation; });
            when(telegram.send(anyString())).thenReturn(sent());
            service = new MixTradeSimulationService(mixes,simulations,settings,telegram,new TelegramMessageFormatter(),market,new BestMixRankingPolicy());
        }
        void useMixes(BestMethodMix... rows) { when(mixes.findByCoinIdAndHorizonSecondsAndSignalVersionOrderByMixSizeAscRankAsc(1,900,3)).thenReturn(List.of(rows)); }
        BestMethodMix mix(int tp,int rank,String... codes) { return mix(tp,rank,TpSlLevels.defaults().tp(tp),codes); }
        BestMethodMix mix(int tp,int rank,BigDecimal target,String... codes) { BestMethodMix value=new BestMethodMix(coin,900,codes.length,rank,List.of(codes),Collections.nCopies(codes.length,1),List.of(codes),50,30,35,.5,tp,target); ReflectionTestUtils.setField(value,"id",ids.incrementAndGet()); return value; }
        MixTradeSimulation simulation(Direction direction) { MixTradeSimulation s=new MixTradeSimulation(coin,mix,direction,2,new BigDecimal("100"),TpSlLevels.defaults(),Instant.EPOCH); ReflectionTestUtils.setField(s,"id",99L); s.telegramMessage(42L); return s; }
        List<Prediction> predictions(Direction direction,String... codes) { return Arrays.stream(codes).map(code -> prediction(code,direction)).toList(); }
        Prediction prediction(String code,Direction direction) { return new Prediction(null,coin,code,1,code,Instant.EPOCH,new BigDecimal("100"),Instant.EPOCH,new BigDecimal("100"),direction,Duration.ofMinutes(15),"5m"); }
        TelegramNotificationService.DeliveryResult sent() { return new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.SENT,42L,null); }
    }
}
