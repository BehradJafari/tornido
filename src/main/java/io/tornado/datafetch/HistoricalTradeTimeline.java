package io.tornado.datafetch;

import java.time.*;
import java.util.*;

/** Sorted, locally searchable Binance aggregate trades retained for requested research timestamps. */
public final class HistoricalTradeTimeline {
    private final List<BinanceMarketDataClient.AggregateTrade> trades;
    private final Duration maximumDelay;

    public HistoricalTradeTimeline(Collection<BinanceMarketDataClient.AggregateTrade> trades,Duration maximumDelay){
        this.trades=trades.stream().collect(java.util.stream.Collectors.toMap(BinanceMarketDataClient.AggregateTrade::id,x->x,(a,b)->a,LinkedHashMap::new)).values().stream().sorted(Comparator.comparing(BinanceMarketDataClient.AggregateTrade::observedAt).thenComparingLong(BinanceMarketDataClient.AggregateTrade::id)).toList();
        this.maximumDelay=Objects.requireNonNull(maximumDelay);
    }

    public Optional<BinanceMarketDataClient.AggregateTrade> tradeAtOrAfter(Instant requestedAt){
        int low=0,high=trades.size();
        while(low<high){int middle=(low+high)>>>1;if(trades.get(middle).observedAt().isBefore(requestedAt))low=middle+1;else high=middle;}
        if(low==trades.size())return Optional.empty();
        var trade=trades.get(low);return Duration.between(requestedAt,trade.observedAt()).compareTo(maximumDelay)<=0?Optional.of(trade):Optional.empty();
    }

    public int size(){return trades.size();}
}
