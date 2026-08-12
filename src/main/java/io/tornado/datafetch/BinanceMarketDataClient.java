package io.tornado.datafetch;

import com.fasterxml.jackson.databind.JsonNode;
import io.tornado.config.TornadoProperties;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.ta4j.core.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Component
public class BinanceMarketDataClient {
    private static final Logger log=LoggerFactory.getLogger(BinanceMarketDataClient.class);
    private final RestClient rest; private final TornadoProperties props;
    public BinanceMarketDataClient(RestClient.Builder builder,TornadoProperties props){this.props=props;rest=builder.baseUrl(props.binance().restBaseUrl()).build();}
    public BarSeries candles(String pair){
        JsonNode rows=getWithRetry("/api/v3/klines?symbol={pair}&interval={interval}&limit={limit}",pair,props.binance().candleInterval(),props.binance().candleLimit());
        BarSeries series=new BaseBarSeriesBuilder().withName(pair).build();
        Duration period=intervalDuration(props.binance().candleInterval());
        long now=System.currentTimeMillis();
        for(JsonNode r:rows){long closeTime=r.get(6).asLong();if(closeTime>now)continue;series.barBuilder().timePeriod(period).endTime(Instant.ofEpochMilli(closeTime)).openPrice(r.get(1).asText()).highPrice(r.get(2).asText()).lowPrice(r.get(3).asText()).closePrice(r.get(4).asText()).volume(r.get(5).asText()).add();}
        if(series.isEmpty())throw new IllegalStateException("Binance returned no completed candles for "+pair);
        return series;
    }
    public BigDecimal price(String pair){return new BigDecimal(getWithRetry("/api/v3/ticker/price?symbol={pair}",pair).get("price").asText());}
    public BigDecimal priceAt(String pair,Instant target){
        JsonNode trades=getWithRetry("/api/v3/aggTrades?symbol={pair}&startTime={start}&endTime={end}&limit=1",pair,target.toEpochMilli(),target.plus(Duration.ofHours(1)).minusMillis(1).toEpochMilli());
        if(trades==null||!trades.isArray()||trades.isEmpty())throw new IllegalStateException("No Binance trade found near "+target+" for "+pair);
        return new BigDecimal(trades.get(0).get("p").asText());
    }
    public PriceRange priceRange(String pair,Instant from,Instant to){
        BigDecimal low=null,high=null;long cursor=from.toEpochMilli(),end=to.toEpochMilli();
        while(cursor<=end){JsonNode rows=getWithRetry("/api/v3/klines?symbol={pair}&interval=1m&startTime={start}&endTime={end}&limit=1000",pair,cursor,end);if(rows==null||!rows.isArray()||rows.isEmpty())break;for(JsonNode row:rows){BigDecimal rowLow=new BigDecimal(row.get(3).asText()),rowHigh=new BigDecimal(row.get(2).asText());low=low==null?rowLow:low.min(rowLow);high=high==null?rowHigh:high.max(rowHigh);}long next=rows.get(rows.size()-1).get(0).asLong()+60_000;if(next<=cursor)break;cursor=next;if(rows.size()<1000)break;}
        if(low==null||high==null)throw new IllegalStateException("No Binance price path found for "+pair+" between "+from+" and "+to);
        return new PriceRange(low,high);
    }
    public List<Candle> chart(String pair,String interval,int limit){JsonNode rows=getWithRetry("/api/v3/klines?symbol={pair}&interval={interval}&limit={limit}",pair,interval,Math.min(500,Math.max(20,limit)));List<Candle> out=new ArrayList<>();for(JsonNode r:rows)out.add(new Candle(r.get(0).asLong(),r.get(1).asText(),r.get(2).asText(),r.get(3).asText(),r.get(4).asText(),r.get(5).asText()));return out;}
    private JsonNode getWithRetry(String uri,Object... vars){
        RuntimeException last=null;
        for(int i=0;i<3;i++) try{return rest.get().uri(uri,vars).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);}catch(RuntimeException e){last=e;log.warn("Binance request failed (attempt {}): {}",i+1,e.getMessage());try{Thread.sleep(250L*(1L<<i));}catch(InterruptedException x){Thread.currentThread().interrupt();throw e;}}
        throw last;
    }
    private Duration intervalDuration(String value){long n=Long.parseLong(value.substring(0,value.length()-1));return switch(value.charAt(value.length()-1)){case 'm'->Duration.ofMinutes(n);case 'h'->Duration.ofHours(n);case 'd'->Duration.ofDays(n);default->throw new IllegalArgumentException("Unsupported candle interval "+value);};}
    public record Candle(long time,String open,String high,String low,String close,String volume){}
    public record PriceRange(BigDecimal low,BigDecimal high){}
}
