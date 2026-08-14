package io.tornado.datafetch;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class HistoricalTradeTimelineTest{
 @Test void choosesFirstTradeAtOrAfterRequestedTimestamp(){Instant requested=Instant.parse("2026-01-01T10:15:37Z");var timeline=new HistoricalTradeTimeline(List.of(trade(1,"100",requested.minusMillis(100)),trade(2,"101",requested.plusMillis(20)),trade(3,"102",requested.plusMillis(500))),Duration.ofMinutes(5));assertThat(timeline.tradeAtOrAfter(requested).orElseThrow().price()).isEqualByComparingTo("101");}
 @Test void targetLookupNeverUsesTradeImmediatelyBeforeTarget(){Instant target=Instant.parse("2026-01-01T10:30:37Z");var timeline=new HistoricalTradeTimeline(List.of(trade(1,"105",target.minusMillis(1)),trade(2,"106",target.plusMillis(100))),Duration.ofMinutes(5));assertThat(timeline.tradeAtOrAfter(target).orElseThrow().price()).isEqualByComparingTo("106");}
 @Test void rejectsEntryOrTargetWhenFirstLaterTradeExceedsMaximumDelay(){Instant requested=Instant.parse("2026-01-01T10:15:37Z");var timeline=new HistoricalTradeTimeline(List.of(trade(1,"101",requested.plus(Duration.ofMinutes(9)))),Duration.ofMinutes(5));assertThat(timeline.tradeAtOrAfter(requested)).isEmpty();}
 private BinanceMarketDataClient.AggregateTrade trade(long id,String price,Instant at){return new BinanceMarketDataClient.AggregateTrade(id,new BigDecimal(price),at);}
}
