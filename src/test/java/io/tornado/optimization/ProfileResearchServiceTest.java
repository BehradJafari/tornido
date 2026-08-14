package io.tornado.optimization;

import io.tornado.datafetch.*;
import io.tornado.persistence.*;
import io.tornado.strategies.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.ta4j.core.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileResearchServiceTest {
    @Test void usesFirstTradesAfterExactSnapshotAndTargetWithoutFutureAnalysisCandles(){
        var service=service(mock(BinanceMarketDataClient.class),mock(CoinRepository.class),mock(AppSettingsRepository.class));Coin coin=coin();
        Instant base=Instant.parse("2026-01-01T00:00:00Z");BarSeries analysis=analysis(base,100,Duration.ofMinutes(15));Instant from=base.plusSeconds(81*900L+37),to=from.plus(Duration.ofHours(3));
        List<BinanceMarketDataClient.AggregateTrade>trades=new ArrayList<>();long id=1;for(Instant time:service.requestedPriceTimes(from,to,3600,Duration.ofMinutes(15))){trades.add(new BinanceMarketDataClient.AggregateTrade(id++,new BigDecimal(100+id),time.plusMillis(20)));}
        var timeline=new HistoricalTradeTimeline(trades,BinanceMarketDataClient.MAX_HISTORICAL_PRICE_DELAY);var rows=service.observations(coin,StrategyDefinition.EMA_20,"20",3600,from,to,Duration.ofMinutes(15),analysis,timeline);
        assertThat(rows).hasSizeGreaterThanOrEqualTo(5);var first=rows.getFirst();
        assertThat(first.executionAt()).isEqualTo(from);assertThat(first.entryObservedAt()).isEqualTo(from.plusMillis(20));assertThat(first.entryDelayMilliseconds()).isEqualTo(20);
        assertThat(first.targetAt()).isEqualTo(from.plus(Duration.ofHours(1)));assertThat(first.exitObservedAt()).isEqualTo(first.targetAt().plusMillis(20));assertThat(first.exitDelayMilliseconds()).isEqualTo(20);
        assertThat(first.signalAt()).isBefore(first.executionAt());assertThat(first.signalAt()).isEqualTo(base.plusSeconds(81*900L));
        assertThat(rows.get(1).executionAt()).isEqualTo(first.executionAt().plus(Duration.ofMinutes(15)));assertThat(rows.get(1).executionAt()).isBefore(first.targetAt());
    }

    @Test void downloadsOneTradeTimelineForAllTimeframesAndParameters(){
        BinanceMarketDataClient market=mock(BinanceMarketDataClient.class);CoinRepository coins=mock(CoinRepository.class);AppSettingsRepository settings=mock(AppSettingsRepository.class);ProfileResearchService service=service(market,coins,settings);Coin coin=coin();
        Instant from=Instant.parse("2026-01-01T00:00:37Z"),to=from.plus(Duration.ofHours(4));AppSettings configuration=new AppSettings(900,900);when(settings.findById(1)).thenReturn(Optional.of(configuration));when(coins.findBySymbolIgnoreCase("BTC")).thenReturn(Optional.of(coin));
        BarSeries series=analysis(from.minus(Duration.ofHours(10)),700,Duration.ofMinutes(1));when(market.historicalCandles(eq("BTCUSDT"),anyString(),any(),eq(to))).thenReturn(series);
        SortedSet<Instant>requested=service.requestedPriceTimes(from,to,60,Duration.ofMinutes(15));List<BinanceMarketDataClient.AggregateTrade>trades=new ArrayList<>();long id=1;for(Instant time:requested)trades.add(new BinanceMarketDataClient.AggregateTrade(id++,new BigDecimal("100"),time.plusMillis(1)));when(market.historicalTradeTimeline(eq("BTCUSDT"),anyCollection())).thenReturn(new HistoricalTradeTimeline(trades,BinanceMarketDataClient.MAX_HISTORICAL_PRICE_DELAY));
        service.research(ProfileScope.COIN_SPECIFIC,"BTC","EMA_20",60,from,to);
        verify(market,times(1)).historicalTradeTimeline(eq("BTCUSDT"),anyCollection());verify(market,times(2)).historicalCandles(eq("BTCUSDT"),anyString(),any(),eq(to));
    }

    @Test void rejectsObservationWhenEntryIsValidButTargetTradeIsTooLate(){
        var service=service(mock(BinanceMarketDataClient.class),mock(CoinRepository.class),mock(AppSettingsRepository.class));Instant base=Instant.parse("2026-01-01T00:00:00Z"),from=base.plusSeconds(81*900L+37),target=from.plusSeconds(60);BarSeries analysis=analysis(base,100,Duration.ofMinutes(15));
        var timeline=new HistoricalTradeTimeline(List.of(new BinanceMarketDataClient.AggregateTrade(1,new BigDecimal("100"),from.plusMillis(10)),new BinanceMarketDataClient.AggregateTrade(2,new BigDecimal("101"),target.plus(Duration.ofMinutes(6)))),BinanceMarketDataClient.MAX_HISTORICAL_PRICE_DELAY);
        assertThat(service.observations(coin(),StrategyDefinition.EMA_20,"20",60,from,target,Duration.ofMinutes(15),analysis,timeline)).isEmpty();
    }

    private ProfileResearchService service(BinanceMarketDataClient market,CoinRepository coins,AppSettingsRepository settings){return new ProfileResearchService(market,coins,settings,mock(ProfileSelectionService.class));}
    private Coin coin(){Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);return coin;}
    private BarSeries analysis(Instant base,int count,Duration period){var series=new BaseBarSeriesBuilder().withName("analysis").build();for(int i=0;i<count;i++){Instant end=base.plus(period.multipliedBy(i+1));series.barBuilder().timePeriod(period).endTime(end).openPrice(100+i).highPrice(102+i).lowPrice(99+i).closePrice(101+i).volume(1000).add();}return series;}
}
