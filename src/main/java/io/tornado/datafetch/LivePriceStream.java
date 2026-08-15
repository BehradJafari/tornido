package io.tornado.datafetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tornado.config.TornadoProperties;
import io.tornado.persistence.CoinRepository;
import io.tornado.reporting.MixTradeSimulationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.net.URI; import java.net.http.*; import java.util.concurrent.*; import java.util.stream.Collectors;
import java.util.Map;

@Component
public class LivePriceStream {
    private static final Logger log=LoggerFactory.getLogger(LivePriceStream.class);
    private final CoinRepository coins; private final TornadoProperties props; private final ObjectMapper json;private final MixTradeSimulationService simulations;
    private final HttpClient client=HttpClient.newHttpClient(); private final Sinks.Many<PriceTick> sink=Sinks.many().multicast().directBestEffort();
    private final Map<String,PriceTick> latest=new ConcurrentHashMap<>();private final Map<String,Long> lastAggregateIds=new ConcurrentHashMap<>();
    private volatile WebSocket socket; private volatile String subscribed="";
    public LivePriceStream(CoinRepository coins,TornadoProperties props,ObjectMapper json,MixTradeSimulationService simulations){this.coins=coins;this.props=props;this.json=json;this.simulations=simulations;}
    public Flux<PriceTick> flux(){return sink.asFlux();}
    public Map<String,PriceTick> latest(){return Map.copyOf(latest);}
    @Scheduled(initialDelay=1000,fixedDelay=30000) public synchronized void ensureSubscription(){
        String streams=coins.findAllByActiveTrueOrderBySymbol().stream().map(c->c.getPair().toLowerCase()+"@aggTrade").collect(Collectors.joining("/"));
        if(streams.isBlank()||streams.equals(subscribed)&&socket!=null)return;
        if(socket!=null)socket.sendClose(WebSocket.NORMAL_CLOSURE,"resubscribe");
        subscribed=streams;String url=props.binance().websocketBaseUrl()+"/stream?streams="+streams;
        client.newWebSocketBuilder().buildAsync(URI.create(url),new Listener()).whenComplete((ws,e)->{if(e!=null){log.warn("Binance WebSocket connection failed: {}",e.getMessage());socket=null;}else socket=ws;});
    }
    @PreDestroy void close(){if(socket!=null)socket.abort();}
    private class Listener implements WebSocket.Listener {
        private final StringBuilder text=new StringBuilder();private final java.util.Set<String>recovered=java.util.concurrent.ConcurrentHashMap.newKeySet();
        public void onOpen(WebSocket ws){log.info("Binance live stream connected");ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){text.append(data);if(last)try{var n=json.readTree(text.toString()).get("data");String pair=n.get("s").asText();long aggregateId=n.get("a").asLong(),timestamp=n.get("T").asLong();var tick=new PriceTick(pair,n.get("p").asText(),timestamp);latest.put(pair,tick);sink.tryEmitNext(tick);try{Long previous=lastAggregateIds.get(pair);boolean gap=previous!=null&&aggregateId>previous+1;if(!recovered.contains(pair)||gap){simulations.recover(pair,java.time.Instant.ofEpochMilli(timestamp));recovered.add(pair);}simulations.observe(pair,new java.math.BigDecimal(tick.price()),java.time.Instant.ofEpochMilli(timestamp),aggregateId);lastAggregateIds.put(pair,aggregateId);}catch(Exception e){log.warn("Mix simulation recovery/check failed for {} without affecting live prices: {}",pair,e.getMessage());}}catch(Exception e){log.debug("Bad aggregate-trade message: {}",e.getMessage());}finally{text.setLength(0);}ws.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason){socket=null;return null;}
        public void onError(WebSocket ws,Throwable error){log.warn("Binance live stream error: {}",error.getMessage());socket=null;}
    }
    public record PriceTick(String pair,String price,long timestamp){}
}
