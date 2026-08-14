package io.tornado.optimization;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
import io.tornado.strategies.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.ta4j.core.BaseBarSeriesBuilder;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProfileResearchServiceTest {
    @Test void usesSeparateExecutionTimelineExactHorizonAndProductionSnapshotFrequency(){
        var service=new ProfileResearchService(mock(BinanceMarketDataClient.class),mock(CoinRepository.class),mock(AppSettingsRepository.class),mock(ProfileSelectionService.class));
        Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);
        Instant base=Instant.parse("2026-01-01T00:00:00Z");
        var analysis=new BaseBarSeriesBuilder().withName("analysis").build();
        for(int i=0;i<100;i++){Instant close=base.plusSeconds((i+1)*900L).minusMillis(1);analysis.barBuilder().timePeriod(Duration.ofMinutes(15)).endTime(close).openPrice(100+i).highPrice(102+i).lowPrice(99+i).closePrice(101+i).volume(1000).add();}
        Instant from=base.plusSeconds(81*900L),to=from.plus(Duration.ofHours(3));
        var execution=new BaseBarSeriesBuilder().withName("execution").build();
        for(int i=0;i<=180;i++){Instant open=from.plusSeconds(i*60L);execution.barBuilder().timePeriod(Duration.ofMinutes(1)).endTime(open.plusSeconds(60).minusMillis(1)).openPrice(1000+i).highPrice(1001+i).lowPrice(999+i).closePrice(1000+i).volume(100).add();}
        var rows=service.observations(coin,StrategyDefinition.EMA_20,"20",3600,from,to,Duration.ofMinutes(15),analysis,execution);
        assertThat(rows).hasSizeGreaterThanOrEqualTo(5);
        assertThat(rows.get(1).executionAt()).isEqualTo(rows.get(0).executionAt().plus(Duration.ofMinutes(15)));
        assertThat(rows.get(1).executionAt()).isBefore(rows.get(0).targetAt());
        assertThat(rows.get(0).targetAt()).isEqualTo(rows.get(0).executionAt().plus(Duration.ofHours(1)));
        assertThat(rows.get(0).entryPrice()).isEqualByComparingTo("1000");
        assertThat(rows.get(0).exitPrice()).isEqualByComparingTo("1060");
        assertThat(rows.get(0).signalAt()).isBefore(rows.get(0).executionAt());
    }
}
