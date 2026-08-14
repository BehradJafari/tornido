package io.tornado.datafetch;

import com.fasterxml.jackson.databind.JsonNode;
import io.tornado.config.TornadoProperties;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.ta4j.core.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Component
public class BinanceMarketDataClient {
    private static final Logger log=LoggerFactory.getLogger(BinanceMarketDataClient.class);
    public static final Duration MAX_HISTORICAL_PRICE_DELAY=Duration.ofMinutes(5);
    private final RestClient rest; private final TornadoProperties props;private final Clock clock;
    @Autowired public BinanceMarketDataClient(RestClient.Builder builder,TornadoProperties props){this(builder,props,Clock.systemUTC());}
    BinanceMarketDataClient(RestClient.Builder builder,TornadoProperties props,Clock clock){this.props=props;this.clock=clock;rest=builder.baseUrl(props.binance().restBaseUrl()).build();}
    public BarSeries candles(String pair){return candles(pair,props.binance().candleInterval());}
    public BarSeries candles(String pair,String interval){
        JsonNode rows=getWithRetry("/api/v3/klines?symbol={pair}&interval={interval}&limit={limit}",pair,interval,props.binance().candleLimit());
        BarSeries series=new BaseBarSeriesBuilder().withName(pair).build();
        Duration period=intervalDuration(interval);
        long now=clock.millis();
        for(JsonNode r:rows){long closeTime=r.get(6).asLong();if(closeTime>now)continue;series.barBuilder().timePeriod(period).endTime(Instant.ofEpochMilli(closeTime)).openPrice(r.get(1).asText()).highPrice(r.get(2).asText()).lowPrice(r.get(3).asText()).closePrice(r.get(4).asText()).volume(r.get(5).asText()).add();}
        if(series.isEmpty())throw new IllegalStateException("Binance returned no completed candles for "+pair);
        return series;
    }
    public BigDecimal price(String pair){return new BigDecimal(getWithRetry("/api/v3/ticker/price?symbol={pair}",pair).get("price").asText());}
    public String candleInterval(){return props.binance().candleInterval();}
    public BarSeries historicalCandles(String pair,String interval,Instant from,Instant to){if(!to.isAfter(from))throw new IllegalArgumentException("Historical candle end must follow start");Duration period=intervalDuration(interval);long cursor=from.toEpochMilli(),end=Math.min(to.toEpochMilli(),clock.millis()),step=period.toMillis();BarSeries series=new BaseBarSeriesBuilder().withName(pair+"-"+interval+"-research").build();while(cursor<end){JsonNode rows=getWithRetry("/api/v3/klines?symbol={pair}&interval={interval}&startTime={start}&endTime={end}&limit=1000",pair,interval,cursor,end);if(rows==null||!rows.isArray()||rows.isEmpty())break;long next=cursor;for(JsonNode r:rows){long openTime=r.get(0).asLong(),closeTime=r.get(6).asLong();next=Math.max(next,openTime+step);if(closeTime>end||closeTime>clock.millis())continue;series.barBuilder().timePeriod(period).endTime(Instant.ofEpochMilli(closeTime)).openPrice(r.get(1).asText()).highPrice(r.get(2).asText()).lowPrice(r.get(3).asText()).closePrice(r.get(4).asText()).volume(r.get(5).asText()).add();}if(next<=cursor||rows.size()<1000)break;cursor=next;}if(series.isEmpty())throw new IllegalStateException("Binance returned no completed historical candles for "+pair+" "+interval);return series;}
    public TimedPrice priceAt(String pair,Instant target){
        long targetMillis=target.toEpochMilli(),window=MAX_HISTORICAL_PRICE_DELAY.toMillis();
        JsonNode beforeRows=getWithRetry("/api/v3/aggTrades?symbol={pair}&endTime={end}&limit=1",pair,targetMillis);
        JsonNode afterRows=getWithRetry("/api/v3/aggTrades?symbol={pair}&startTime={start}&endTime={end}&limit=1",pair,targetMillis,targetMillis+window);
        JsonNode before=first(beforeRows),after=first(afterRows),trade=nearest(targetMillis,before,after);
        if(trade==null)throw new IllegalStateException("No Binance trade within "+window+"ms of target "+target+" for "+pair);
        Instant observedAt=Instant.ofEpochMilli(trade.get("T").asLong());long delay=Duration.between(target,observedAt).toMillis();
        if(Math.abs(delay)>window)throw new IllegalStateException("Nearest Binance trade for "+pair+" is "+Math.abs(delay)+"ms "+(delay<0?"before":"after")+" target "+target);
        return new TimedPrice(new BigDecimal(trade.get("p").asText()),observedAt,delay);
    }
    private JsonNode first(JsonNode rows){return rows!=null&&rows.isArray()&&!rows.isEmpty()?rows.get(0):null;}
    private JsonNode nearest(long targetMillis,JsonNode before,JsonNode after){if(before==null)return after;if(after==null)return before;long beforeDistance=Math.abs(targetMillis-before.get("T").asLong()),afterDistance=Math.abs(after.get("T").asLong()-targetMillis);return beforeDistance<=afterDistance?before:after;}
    public PriceRange priceRange(String pair,Instant from,Instant to){
        if(to.isBefore(from))throw new IllegalArgumentException("price range end precedes start");
        Instant firstMinute=from.truncatedTo(java.time.temporal.ChronoUnit.MINUTES),lastMinute=to.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        MutableRange range=new MutableRange();
        if(firstMinute.equals(lastMinute)){addTrades(pair,from,to,range);}else{
            addTrades(pair,from,firstMinute.plus(Duration.ofMinutes(1)).minusMillis(1),range);
            addKlines(pair,firstMinute.plus(Duration.ofMinutes(1)),lastMinute.minusMillis(1),range);
            addTrades(pair,lastMinute,to,range);
        }
        if(range.low==null)throw new IllegalStateException("No Binance price observations for "+pair+" between "+from+" and "+to);
        return new PriceRange(range.low,range.high);
    }
    public List<AggregateTrade> historicalTrades(String pair,Instant from,Instant to){
        if(to.isBefore(from))throw new IllegalArgumentException("historical trade end precedes start");
        List<AggregateTrade> result=new ArrayList<>();Instant cursor=from;
        while(!cursor.isAfter(to)){Instant chunkEnd=cursor.plus(Duration.ofHours(1)).minusMillis(1);if(chunkEnd.isAfter(to))chunkEnd=to;addHistoricalTrades(pair,cursor,chunkEnd,result);cursor=chunkEnd.plusMillis(1);}
        return result.stream().collect(java.util.stream.Collectors.toMap(AggregateTrade::id,x->x,(a,b)->a,LinkedHashMap::new)).values().stream().filter(x->!x.observedAt().isBefore(from)&&!x.observedAt().isAfter(to)).sorted(Comparator.comparing(AggregateTrade::observedAt).thenComparingLong(AggregateTrade::id)).toList();
    }
    public HistoricalTradeTimeline historicalTradeTimeline(String pair,Collection<Instant>requestedTimes){if(requestedTimes==null||requestedTimes.isEmpty())return new HistoricalTradeTimeline(List.of(),MAX_HISTORICAL_PRICE_DELAY);Map<Instant,List<Instant>>hourBuckets=new TreeMap<>();for(Instant requested:new TreeSet<>(requestedTimes))hourBuckets.computeIfAbsent(requested.truncatedTo(java.time.temporal.ChronoUnit.HOURS),x->new ArrayList<>()).add(requested);Map<Long,AggregateTrade>selected=new LinkedHashMap<>();for(List<Instant>bucket:hourBuckets.values()){Instant start=bucket.getFirst(),end=bucket.getLast().plus(MAX_HISTORICAL_PRICE_DELAY);List<AggregateTrade>available=historicalTrades(pair,start,end);HistoricalTradeTimeline local=new HistoricalTradeTimeline(available,MAX_HISTORICAL_PRICE_DELAY);for(Instant requested:bucket)local.tradeAtOrAfter(requested).ifPresent(x->selected.putIfAbsent(x.id(),x));}return new HistoricalTradeTimeline(selected.values(),MAX_HISTORICAL_PRICE_DELAY);}
    private void addHistoricalTrades(String pair,Instant from,Instant to,List<AggregateTrade> out){
        JsonNode rows=getWithRetry("/api/v3/aggTrades?symbol={pair}&startTime={start}&endTime={end}&limit=1000",pair,from.toEpochMilli(),to.toEpochMilli());long previousPageLastId=-1;
        while(rows!=null&&rows.isArray()&&!rows.isEmpty()){
            boolean beyond=false;long pageLastId=-1;
            for(JsonNode trade:rows){long time=trade.get("T").asLong(),id=trade.get("a").asLong();pageLastId=Math.max(pageLastId,id);if(time>to.toEpochMilli()){beyond=true;break;}if(time>=from.toEpochMilli())out.add(new AggregateTrade(id,new BigDecimal(trade.get("p").asText()),Instant.ofEpochMilli(time)));}
            if(beyond||rows.size()<1000||pageLastId<0)break;
            if(pageLastId<=previousPageLastId)throw new IllegalStateException("Binance aggregate-trade pagination did not advance for "+pair);
            previousPageLastId=pageLastId;rows=getWithRetry("/api/v3/aggTrades?symbol={pair}&fromId={fromId}&limit=1000",pair,pageLastId+1);
        }
    }
    private void addKlines(String pair,Instant from,Instant to,MutableRange range){if(to.isBefore(from))return;long cursor=from.toEpochMilli(),end=to.toEpochMilli();while(cursor<=end){JsonNode rows=getWithRetry("/api/v3/klines?symbol={pair}&interval=1m&startTime={start}&endTime={end}&limit=1000",pair,cursor,end);if(rows==null||!rows.isArray()||rows.isEmpty())break;for(JsonNode row:rows)range.add(new BigDecimal(row.get(3).asText()),new BigDecimal(row.get(2).asText()));long next=rows.get(rows.size()-1).get(0).asLong()+60_000;if(next<=cursor)break;cursor=next;if(rows.size()<1000)break;}}
    private void addTrades(String pair,Instant from,Instant to,MutableRange range){if(to.isBefore(from))return;JsonNode rows=getWithRetry("/api/v3/aggTrades?symbol={pair}&startTime={start}&endTime={end}&limit=1000",pair,from.toEpochMilli(),to.toEpochMilli());while(rows!=null&&rows.isArray()&&!rows.isEmpty()){long lastId=-1;for(JsonNode trade:rows){long time=trade.get("T").asLong();lastId=trade.get("a").asLong();if(time>=from.toEpochMilli()&&time<=to.toEpochMilli())range.add(new BigDecimal(trade.get("p").asText()));}if(rows.size()<1000||lastId<0)break;rows=getWithRetry("/api/v3/aggTrades?symbol={pair}&fromId={fromId}&limit=1000",pair,lastId+1);if(rows.isEmpty()||rows.get(0).get("T").asLong()>to.toEpochMilli())break;}}
    public List<Candle> chart(String pair,String interval,int limit){JsonNode rows=getWithRetry("/api/v3/klines?symbol={pair}&interval={interval}&limit={limit}",pair,interval,Math.min(500,Math.max(20,limit)));List<Candle> out=new ArrayList<>();for(JsonNode r:rows)out.add(new Candle(r.get(0).asLong(),r.get(1).asText(),r.get(2).asText(),r.get(3).asText(),r.get(4).asText(),r.get(5).asText()));return out;}
    private JsonNode getWithRetry(String uri,Object... vars){
        RuntimeException last=null;
        for(int i=0;i<3;i++) try{return rest.get().uri(uri,vars).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);}catch(RuntimeException e){last=e;log.warn("Binance request failed (attempt {}): {}",i+1,e.getMessage());try{Thread.sleep(250L*(1L<<i));}catch(InterruptedException x){Thread.currentThread().interrupt();throw e;}}
        throw last;
    }
    private Duration intervalDuration(String value){long n=Long.parseLong(value.substring(0,value.length()-1));return switch(value.charAt(value.length()-1)){case 'm'->Duration.ofMinutes(n);case 'h'->Duration.ofHours(n);case 'd'->Duration.ofDays(n);default->throw new IllegalArgumentException("Unsupported candle interval "+value);};}
    public record Candle(long time,String open,String high,String low,String close,String volume){}
    public record TimedPrice(BigDecimal price,Instant observedAt,long delayMilliseconds){}
    public record AggregateTrade(long id,BigDecimal price,Instant observedAt){}
    public record PriceRange(BigDecimal low,BigDecimal high){}
    private static class MutableRange {BigDecimal low,high;void add(BigDecimal value){add(value,value);}void add(BigDecimal rowLow,BigDecimal rowHigh){low=low==null?rowLow:low.min(rowLow);high=high==null?rowHigh:high.max(rowHigh);}}
}
