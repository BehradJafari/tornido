package io.tornado.reporting;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.notification.TelegramMessageFormatter;
import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;

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

    @Test void differentCurrentMixesAcrossAllTpLevelsCreateOnlyStrongestSimulation() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(1, 1, "A", "B", "C"), f.mix(2, 1, "D", "E", "F"), f.mix(3, 1, "G", "H", "I"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C","D","E","F","G","H","I"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations).saveAndFlush(any());
    }

    @Test void sameCombinationAcrossTp1AndTp2CreatesOneSimulation() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(2, 1, "A", "B", "C"), f.mix(1, 3, "A", "B", "C"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> saved = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getBestMix().getTpLevel()).isEqualTo(1);
    }

    @Test void qualifyingTp2IsNotSuppressedByBelowThresholdTp1Representative() {
        Fixture f = new Fixture();
        f.configuration.updateMixSignals(10,f.configuration.getTpSlLevels(),new BigDecimal("65"),false);
        f.useMixes(f.mixWithRate(1,1,100,58,"A","B","C"),f.mixWithRate(2,2,100,72,"A","B","C"),f.mixWithRate(3,3,100,68,"A","B","C"));
        f.service.detect(f.coin,f.predictions(Direction.UP,"A","B","C"),Instant.EPOCH,new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> saved=ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRankingTpLevel()).isEqualTo(2);
        assertThat(saved.getValue().getHistoricalTargetHitRate()).isEqualTo(72d);
        assertThat(saved.getValue().isEligibleForNotification()).isTrue();
        verify(f.events).publishEvent(any(OpportunityCommittedEvent.class));
    }

    @Test void allTpRowsBelowThresholdCreateNoAcceptedSimulationOrEvent() {
        Fixture f = new Fixture();
        f.configuration.updateMixSignals(10,f.configuration.getTpSlLevels(),new BigDecimal("65"),false);
        f.useMixes(f.mixWithRate(1,1,100,58,"A","B","C"),f.mixWithRate(2,2,100,62,"A","B","C"),f.mixWithRate(3,3,100,64,"A","B","C"));
        f.service.detect(f.coin,f.predictions(Direction.UP,"A","B","C"),Instant.EPOCH,new BigDecimal("100"));
        verify(f.simulations,never()).saveAndFlush(any());
        verify(f.events,never()).publishEvent(any());
    }

    @Test void samplesBelowCurrentMinimumCreateNoAcceptedSimulation() {
        Fixture f = new Fixture();
        f.configuration.updateMixSignals(100,f.configuration.getTpSlLevels(),new BigDecimal("50"),false);
        f.useMixes(f.mixWithRate(1,1,50,40,"A","B","C"));
        f.service.detect(f.coin,f.predictions(Direction.UP,"A","B","C"),Instant.EPOCH,new BigDecimal("100"));
        verify(f.simulations,never()).saveAndFlush(any());
        verify(f.events,never()).publishEvent(any());
    }

    @Test void threeMethodMixWithTwoAvailableUpVotesIsDecisive() {
        Fixture f=new Fixture();f.useMixes(f.mix(1,1,"A","B","C"));
        f.service.detect(f.coin,f.predictions(Direction.UP,"A","B"),Instant.EPOCH,new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> saved=ArgumentCaptor.forClass(MixTradeSimulation.class);verify(f.simulations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAgreementCount()).isEqualTo(2);verify(f.events).publishEvent(any(OpportunityCommittedEvent.class));
    }

    @Test void fourMethodMixWithOnlyTwoUpVotesAndOneDownIsNotDecisive() {
        Fixture f=new Fixture();f.useMixes(f.mix(1,1,"A","B","C","D"));
        List<Prediction> current=new ArrayList<>(f.predictions(Direction.UP,"A","B"));current.add(f.prediction("D",Direction.DOWN));
        f.service.detect(f.coin,current,Instant.EPOCH,new BigDecimal("100"));
        verify(f.simulations,never()).saveAndFlush(any());verify(f.events,never()).publishEvent(any());
    }

    @Test void sameCombinationAcrossAllTpLevelsCreatesOneSimulationAndOneTelegramMessage() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(3, 1, "A", "B", "C"), f.mix(1, 2, "A", "B", "C"), f.mix(2, 1, "A", "B", "C"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations).saveAndFlush(any());
        verify(f.events).publishEvent(any(OpportunityCommittedEvent.class));
    }

    @Test void oppositeConsensusDirectionCreatesIndependentSimulations() {
        Fixture f = new Fixture();
        f.useMixes(f.mix(1, 1, "A", "B", "C"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        f.service.detect(f.coin, f.predictions(Direction.DOWN, "A","B","C"), Instant.EPOCH.plusSeconds(1), new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> saved = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(MixTradeSimulation::getDirection).containsExactly(Direction.UP, Direction.DOWN);
    }

    @Test void staleTargetPercentRankingIsIgnored() {
        Fixture f = new Fixture();
        BestMethodMix stale = f.mix(2, 1, new BigDecimal("0.80"), "A", "B", "C");
        f.useMixes(stale);
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations, never()).saveAndFlush(any());
        verifyNoInteractions(f.telegram);
    }

    @Test void repeatedQualifyingSnapshotsCreateIndependentTradesAndNotifications() {
        Fixture f = new Fixture();
        f.useMixes(f.mix);
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH.plusSeconds(900), new BigDecimal("101"));
        ArgumentCaptor<MixTradeSimulation> saved = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(MixTradeSimulation::getOpenedAt)
                .containsExactly(Instant.EPOCH, Instant.EPOCH.plusSeconds(900));
        assertThat(saved.getAllValues()).extracting(MixTradeSimulation::getActiveKey).containsOnlyNulls();
        verify(f.events, times(2)).publishEvent(any(OpportunityCommittedEvent.class));
    }

    @Test void persistenceFailureNeverPublishesNotificationOrCallsTelegram() {
        Fixture f=new Fixture();f.useMixes(f.mix);doThrow(new IllegalStateException("commit preparation failed")).when(f.simulations).saveAndFlush(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->f.service.detect(f.coin,f.predictions(Direction.UP,"A","B","C"),Instant.EPOCH,new BigDecimal("100"))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.events);verify(f.telegram,never()).send(anyString());
    }

    @Test void activePositionRaceRemovesProvisionalSimulationAndPublishesNoNotification() {
        Fixture f = new Fixture();
        f.useMixes(f.mix);
        when(f.activeLocks.tryOpen(any(), any(), any(), any(), any()))
                .thenReturn(new ActiveSignalLockService.AdmissionResult(
                        false, null, SignalNotificationAuditReason.POSITION_ALREADY_OPEN));

        f.service.detect(f.coin, f.predictions(Direction.UP, "A", "B", "C"),
                Instant.EPOCH, new BigDecimal("100"));

        ArgumentCaptor<MixTradeSimulation> provisional = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations).saveAndFlush(provisional.capture());
        verify(f.simulations).delete(provisional.getValue());
        verify(f.simulations).flush();
        verify(f.events, never()).publishEvent(any());
    }

    @Test void shorterReportingHorizonNeverAttemptsLiveAdmission() {
        Fixture f = new Fixture();
        BestMethodMix shortMix = new BestMethodMix(f.coin, 900, 3, 1,
                List.of("A", "B", "C"), List.of(1, 1, 1), List.of("A", "B", "C"),
                50, 30, 35, .5, 1, TpSlLevels.defaults().tp1());
        when(f.mixes.findByCoinIdAndHorizonSecondsAndSignalVersionOrderByMixSizeAscRankAsc(1, 900, 3))
                .thenReturn(List.of(shortMix));
        List<Prediction> predictions = List.of(
                new Prediction(null, f.coin, "A", 1, "A", Instant.EPOCH, BigDecimal.ONE,
                        Instant.EPOCH, BigDecimal.ONE, Direction.UP, Duration.ofMinutes(15), "5m"));

        f.service.detect(f.coin, predictions, Instant.EPOCH, new BigDecimal("100"));

        verifyNoInteractions(f.activeLocks);
        verify(f.simulations, never()).saveAndFlush(any());
    }

    @Test void qualifyingConsensusPersistsConfiguredLadder() {
        Fixture f = new Fixture();
        f.useMixes(f.mix);
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> capture = ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations).saveAndFlush(capture.capture());
        assertThat(capture.getValue().getTelegramMessageId()).isNull();
        assertThat(capture.getValue().getTp3Price()).isEqualByComparingTo("101");
        verify(f.events).publishEvent(any(OpportunityCommittedEvent.class));
    }

    @Test void belowThresholdOpportunityIsNotAcceptedOrSent() {
        Fixture f = new Fixture();
        f.configuration.updateMixSignals(1, f.configuration.getTpSlLevels(), new BigDecimal("65"), false);
        f.useMixes(f.mix);
        f.service.detect(f.coin, f.predictions(Direction.UP, "A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        verify(f.simulations,never()).saveAndFlush(any());
        verify(f.events, never()).publishEvent(any(OpportunityCommittedEvent.class));
    }

    @Test void eligibleOpportunityOnlyPublishesAfterCommitEventAndDoesNotSendInline() {
        Fixture f = new Fixture(); f.useMixes(f.mix);
        f.service.detect(f.coin, f.predictions(Direction.UP,"A","B","C"), Instant.EPOCH, new BigDecimal("100"));
        ArgumentCaptor<MixTradeSimulation> capture=ArgumentCaptor.forClass(MixTradeSimulation.class);
        verify(f.simulations).saveAndFlush(capture.capture());
        assertThat(capture.getValue().isEligibleForNotification()).isTrue();
        assertThat(capture.getValue().getActiveKey()).isNull();
        assertThat(capture.getValue().isTelegramSent()).isFalse();
        assertThat(capture.getValue().getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.NOT_ATTEMPTED);
        verify(f.events).publishEvent(new OpportunityCommittedEvent(capture.getValue().getId()));
        verify(f.telegram,never()).send(anyString());
    }

    @Test void oneTickCrossingAllTargetsProducesOneTerminalEdit() {
        Fixture f = new Fixture(); MixTradeSimulation s = f.simulation(Direction.UP);
        when(f.simulations.lockOpenByPair("BTCUSDT")).thenReturn(List.of(s)); when(f.telegram.edit(eq(42L),anyString())).thenReturn(f.sent());
        f.service.observe("BTCUSDT",new BigDecimal("101.2"),Instant.EPOCH.plusSeconds(60));
        assertThat(s.getStatus()).isEqualTo(MixTradeSimulation.Status.TP3_HIT); assertThat(s.getTp1HitAt()).isEqualTo(s.getTp3HitAt());
        verify(f.telegram).edit(eq(42L),messageContaining("TRADE SUCCESS · TP1 HIT FIRST","✅ 2️⃣ TP2","✅ 3️⃣ TP3")); verify(f.telegram,never()).send(anyString());
    }

    @Test void stopTerminalEditFallsBackWithoutLosingState() {
        Fixture f = new Fixture(); MixTradeSimulation s = f.simulation(Direction.DOWN);
        when(f.simulations.lockOpenByPair("BTCUSDT")).thenReturn(List.of(s)); when(f.telegram.edit(eq(42L),anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.FAILED,null,"edit failed"));
        f.service.observe("BTCUSDT",new BigDecimal("101.2"),Instant.EPOCH.plusSeconds(60));
        assertThat(s.getStatus()).isEqualTo(MixTradeSimulation.Status.SL3_HIT); verify(f.telegram).edit(eq(42L),messageContaining("TRADE FAILED · SL1 HIT FIRST","3️⃣ SL3:","HIT at")); verify(f.telegram).send(messageContaining("TRADE FAILED · SL1 HIT FIRST","3️⃣ SL3:","HIT at"));
    }

    @Test void recoveryPreservesOrderedMixedTouchesAndCoalescesEdit() {
        Fixture f = new Fixture(); MixTradeSimulation s = f.simulation(Direction.UP);
        when(f.simulations.lockOpenByPair("BTCUSDT")).thenReturn(List.of(s));
        when(f.market.historicalTrades(eq("BTCUSDT"),eq(Instant.EPOCH),eq(Instant.EPOCH.plusSeconds(30)))).thenReturn(List.of(new BinanceMarketDataClient.AggregateTrade(1,new BigDecimal("99.4"),Instant.EPOCH.plusSeconds(10)),new BinanceMarketDataClient.AggregateTrade(2,new BigDecimal("100.6"),Instant.EPOCH.plusSeconds(20))));
        when(f.telegram.edit(eq(42L),anyString())).thenReturn(f.sent()); f.service.recover("BTCUSDT",Instant.EPOCH.plusSeconds(30));
        assertThat(s.getStatus()).isEqualTo(MixTradeSimulation.Status.OPEN); assertThat(s.getSl1HitAt()).isEqualTo(Instant.EPOCH.plusSeconds(10)); assertThat(s.getTp1HitAt()).isEqualTo(Instant.EPOCH.plusSeconds(20));
        verify(f.telegram).edit(eq(42L),messageContaining("TRADE FAILED · SL1 HIT FIRST","❌ 1️⃣ SL1","❌ 2️⃣ SL2","✅ 1️⃣ TP1","✅ 2️⃣ TP2"));
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
        final TelegramNotificationService telegram = mock(TelegramNotificationService.class); final BinanceMarketDataClient market = mock(BinanceMarketDataClient.class); final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class); final ActiveSignalLockService activeLocks=mock(ActiveSignalLockService.class);
        final AppSettings configuration = new AppSettings(900,900); final BestMethodMix mix; final MixTradeSimulationService service; final AtomicLong ids = new AtomicLong(10);
        Fixture() {
            ReflectionTestUtils.setField(coin,"id",1L); configuration.updateTelegram(true); when(settings.findById(1)).thenReturn(Optional.of(configuration));
            mix = mix(1,1,"A","B","C");
            when(simulations.saveAndFlush(any())).thenAnswer(invocation -> { MixTradeSimulation simulation=invocation.getArgument(0); ReflectionTestUtils.setField(simulation,"id",ids.incrementAndGet()); return simulation; });
            when(activeLocks.tryOpen(any(),any(),any(),any(),any())).thenAnswer(invocation -> { ActiveSignalLock lock=mock(ActiveSignalLock.class); when(lock.getId()).thenReturn(ids.incrementAndGet()); return new ActiveSignalLockService.AdmissionResult(true,lock,null); });
            when(telegram.configured()).thenReturn(true); when(telegram.send(anyString())).thenReturn(sent());
            BestMixRankingPolicy rankings = new BestMixRankingPolicy();
            service = new MixTradeSimulationService(mixes,simulations,settings,telegram,new TelegramMessageFormatter(),market,rankings,new NotificationEligibilityPolicy(rankings),events,activeLocks);
        }
        void useMixes(BestMethodMix... rows) { when(mixes.findByCoinIdAndHorizonSecondsAndSignalVersionOrderByMixSizeAscRankAsc(1,3600,3)).thenReturn(List.of(rows)); }
        BestMethodMix mix(int tp,int rank,String... codes) { return mix(tp,rank,TpSlLevels.defaults().tp(tp),codes); }
        BestMethodMix mix(int tp,int rank,BigDecimal target,String... codes) { BestMethodMix value=new BestMethodMix(coin,3600,codes.length,rank,List.of(codes),Collections.nCopies(codes.length,1),List.of(codes),50,30,35,.5,tp,target); ReflectionTestUtils.setField(value,"id",ids.incrementAndGet()); return value; }
        BestMethodMix mixWithRate(int tp,int rank,long samples,long hits,String... codes) { BestMethodMix value=new BestMethodMix(coin,3600,codes.length,rank,List.of(codes),Collections.nCopies(codes.length,1),List.of(codes),samples,hits,hits,.5,tp,TpSlLevels.defaults().tp(tp)); ReflectionTestUtils.setField(value,"id",ids.incrementAndGet()); return value; }
        MixTradeSimulation simulation(Direction direction) { MixTradeSimulation s=new MixTradeSimulation(coin,mix,direction,2,new BigDecimal("100"),TpSlLevels.defaults(),Instant.EPOCH); ReflectionTestUtils.setField(s,"id",99L); s.telegramMessage(42L); return s; }
        List<Prediction> predictions(Direction direction,String... codes) { return Arrays.stream(codes).map(code -> prediction(code,direction)).toList(); }
        Prediction prediction(String code,Direction direction) { return new Prediction(null,coin,code,1,code,Instant.EPOCH,new BigDecimal("100"),Instant.EPOCH,new BigDecimal("100"),direction,Duration.ofHours(1),"15m"); }
        TelegramNotificationService.DeliveryResult sent() { return new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.SENT,42L,null); }
    }
}
