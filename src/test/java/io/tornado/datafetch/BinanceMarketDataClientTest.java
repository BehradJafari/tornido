package io.tornado.datafetch;

import io.tornado.config.TornadoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceMarketDataClientTest {
    @Test void excludesTheStillOpenCandle(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        long past=System.currentTimeMillis()-60_000,future=System.currentTimeMillis()+60_000;
        server.expect(requestTo("https://binance.test/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=250")).andRespond(withSuccess("[[0,\"100\",\"102\",\"99\",\"101\",\"10\","+past+"],[0,\"101\",\"103\",\"100\",\"102\",\"10\","+future+"]]",MediaType.APPLICATION_JSON));

        var series=client(builder).candles("BTCUSDT");

        assertThat(series.getBarCount()).isEqualTo(1);
        assertThat(series.getLastBar().getClosePrice().doubleValue()).isEqualTo(101);
        server.verify();
    }

    @Test void acceptsACandleAtTheExactBinanceCloseTimeBoundary(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        Instant now=Instant.parse("2026-01-01T00:05:00Z");long before=now.minusMillis(1).toEpochMilli(),equal=now.toEpochMilli(),after=now.plusMillis(1).toEpochMilli();
        server.expect(requestTo("https://binance.test/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=250")).andRespond(withSuccess("[[0,\"100\",\"102\",\"99\",\"101\",\"10\","+before+"],[0,\"101\",\"103\",\"100\",\"102\",\"10\","+equal+"],[0,\"102\",\"104\",\"101\",\"103\",\"10\","+after+"]]",MediaType.APPLICATION_JSON));

        assertThat(client(builder,Clock.fixed(now,ZoneOffset.UTC)).candles("BTCUSDT").getBarCount()).isEqualTo(2);
        server.verify();
    }

    @Test void readsTheFirstTradeAtTheRequestedHistoricalTarget(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        Instant target=Instant.parse("2026-01-01T00:00:00Z");long end=target.plusSeconds(300).toEpochMilli();
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&endTime="+target.toEpochMilli()+"&limit=1")).andRespond(withSuccess("[{\"p\":\"123.456\",\"T\":"+target.toEpochMilli()+"}]",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&startTime="+target.toEpochMilli()+"&endTime="+end+"&limit=1")).andRespond(withSuccess("[{\"p\":\"123.456\",\"T\":"+target.toEpochMilli()+"}]",MediaType.APPLICATION_JSON));

        var result=client(builder).priceAt("BTCUSDT",target);
        assertThat(result.price()).isEqualByComparingTo(new BigDecimal("123.456"));
        assertThat(result.observedAt()).isEqualTo(target);
        assertThat(result.delayMilliseconds()).isZero();
        server.verify();
    }

    @Test void usesTheCloserTradeOnEitherSideAndKeepsSignedDelay(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        Instant target=Instant.parse("2026-01-01T00:00:00Z");long end=target.plusSeconds(300).toEpochMilli(),before=target.minusSeconds(40).toEpochMilli(),after=target.plusSeconds(108).toEpochMilli();
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&endTime="+target.toEpochMilli()+"&limit=1")).andRespond(withSuccess("[{\"p\":\"122.000\",\"T\":"+before+"}]",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&startTime="+target.toEpochMilli()+"&endTime="+end+"&limit=1")).andRespond(withSuccess("[{\"p\":\"123.456\",\"T\":"+after+"}]",MediaType.APPLICATION_JSON));

        var result=client(builder).priceAt("BTCUSDT",target);assertThat(result.price()).isEqualByComparingTo("122");assertThat(result.observedAt()).isEqualTo(target.minusSeconds(40));assertThat(result.delayMilliseconds()).isEqualTo(-40_000);
        server.verify();
    }

    @Test void rejectsWhenNeitherSideHasATradeWithinFiveMinutes(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        Instant target=Instant.parse("2026-01-01T00:00:00Z");long old=target.minusSeconds(301).toEpochMilli(),end=target.plusSeconds(300).toEpochMilli();
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&endTime="+target.toEpochMilli()+"&limit=1")).andRespond(withSuccess("[{\"p\":\"122\",\"T\":"+old+"}]",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&startTime="+target.toEpochMilli()+"&endTime="+end+"&limit=1")).andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));

        assertThatThrownBy(()->client(builder).priceAt("BTCUSDT",target)).isInstanceOf(IllegalStateException.class).hasMessageContaining("301000ms before target");
        server.verify();
    }

    @Test void exactRangeUsesTradesForPartialBoundaryMinutesAndCandlesOnlyInTheMiddle(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        Instant from=Instant.parse("2026-01-01T00:00:30Z"),firstEnd=Instant.parse("2026-01-01T00:00:59.999Z"),middle=Instant.parse("2026-01-01T00:01:00Z"),middleEnd=Instant.parse("2026-01-01T00:01:59.999Z"),last=Instant.parse("2026-01-01T00:02:00Z"),to=Instant.parse("2026-01-01T00:02:30Z");
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&startTime="+from.toEpochMilli()+"&endTime="+firstEnd.toEpochMilli()+"&limit=1000")).andRespond(withSuccess("[{\"a\":1,\"p\":\"99\",\"T\":"+from.toEpochMilli()+"}]",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://binance.test/api/v3/klines?symbol=BTCUSDT&interval=1m&startTime="+middle.toEpochMilli()+"&endTime="+middleEnd.toEpochMilli()+"&limit=1000")).andRespond(withSuccess("[["+middle.toEpochMilli()+",\"100\",\"110\",\"95\",\"105\",\"10\","+middleEnd.toEpochMilli()+"]]",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&startTime="+last.toEpochMilli()+"&endTime="+to.toEpochMilli()+"&limit=1000")).andRespond(withSuccess("[{\"a\":2,\"p\":\"108\",\"T\":"+to.toEpochMilli()+"}]",MediaType.APPLICATION_JSON));

        var range=client(builder).priceRange("BTCUSDT",from,to);
        assertThat(range.low()).isEqualByComparingTo("95");assertThat(range.high()).isEqualByComparingTo("110");server.verify();
    }

    @Test void historicalTradeReplayPreservesFirstTouchOrder(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        Instant from=Instant.parse("2026-01-01T00:00:00Z"),to=from.plusSeconds(30);
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&startTime="+from.toEpochMilli()+"&endTime="+to.toEpochMilli()+"&limit=1000")).andRespond(withSuccess("[{\"a\":10,\"p\":\"99.4\",\"T\":"+from.plusSeconds(10).toEpochMilli()+"},{\"a\":11,\"p\":\"100.5\",\"T\":"+from.plusSeconds(20).toEpochMilli()+"}]",MediaType.APPLICATION_JSON));
        var trades=client(builder).historicalTrades("BTCUSDT",from,to);
        assertThat(trades).extracting(BinanceMarketDataClient.AggregateTrade::id).containsExactly(10L,11L);
        assertThat(trades).extracting(BinanceMarketDataClient.AggregateTrade::price).containsExactly(new BigDecimal("99.4"),new BigDecimal("100.5"));
        server.verify();
    }

    @Test void historicalTradePaginationHasNoMissingOrDuplicateBoundaryIds(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();Instant from=Instant.parse("2026-01-01T00:00:00Z"),to=from.plusSeconds(2);
        StringBuilder first=new StringBuilder("[");for(int i=0;i<1000;i++){if(i>0)first.append(',');first.append("{\"a\":").append(i).append(",\"p\":\"100\",\"T\":").append(from.plusMillis(i).toEpochMilli()).append('}');}first.append(']');
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&startTime="+from.toEpochMilli()+"&endTime="+to.toEpochMilli()+"&limit=1000")).andRespond(withSuccess(first.toString(),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://binance.test/api/v3/aggTrades?symbol=BTCUSDT&fromId=1000&limit=1000")).andRespond(withSuccess("[{\"a\":1000,\"p\":\"101\",\"T\":"+from.plusMillis(1000).toEpochMilli()+"},{\"a\":1001,\"p\":\"102\",\"T\":"+from.plusMillis(1001).toEpochMilli()+"}]",MediaType.APPLICATION_JSON));
        var trades=client(builder).historicalTrades("BTCUSDT",from,to);assertThat(trades).hasSize(1002);assertThat(trades.getFirst().id()).isZero();assertThat(trades.getLast().id()).isEqualTo(1001);assertThat(trades).extracting(BinanceMarketDataClient.AggregateTrade::id).doesNotHaveDuplicates();server.verify();
    }

    private BinanceMarketDataClient client(RestClient.Builder builder){
        return client(builder,Clock.systemUTC());
    }
    private BinanceMarketDataClient client(RestClient.Builder builder,Clock clock){
        var binance=new TornadoProperties.Binance("https://binance.test","wss://binance.test","5m",250,java.time.Duration.ofHours(24));
        var scheduler=new TornadoProperties.Scheduler(java.time.Duration.ofMinutes(15),java.time.Duration.ofSeconds(10),java.time.Duration.ofMinutes(1),java.time.Duration.ofMinutes(15));
        return new BinanceMarketDataClient(builder,new TornadoProperties(binance,scheduler,List.of()),clock);
    }
}
