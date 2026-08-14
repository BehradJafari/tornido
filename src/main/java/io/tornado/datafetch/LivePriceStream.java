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
    private final Map<String,PriceTick> latest=new ConcurrentHashMap<>();
    private volatile WebSocket socket; private volatile String subscribed="";
    public LivePriceStream(CoinRepository coins,TornadoProperties props,ObjectMapper json,MixTradeSimulationService simulations){this.coins=coins;this.props=props;this.json=json;this.simulations=simulations;}
    public Flux<PriceTick> flux(){return sink.asFlux();}
    public Map<String,PriceTick> latest(){return Map.copyOf(latest);}
    @Scheduled(initialDelay=1000,fixedDelay=30000) public synchronized void ensureSubscription(){
        String streams=coins.findAllByActiveTrueOrderBySymbol().stream().map(c->c.getPair().toLowerCase()+"@ticker").collect(Collectors.joining("/"));
        if(streams.isBlank()||streams.equals(subscribed)&&socket!=null)return;
        if(socket!=null)socket.sendClose(WebSocket.NORMAL_CLOSURE,"resubscribe");
        subscribed=streams;String url=props.binance().websocketBaseUrl()+"/stream?streams="+streams;
        client.newWebSocketBuilder().buildAsync(URI.create(url),new Listener()).whenComplete((ws,e)->{if(e!=null){log.warn("Binance WebSocket connection failed: {}",e.getMessage());socket=null;}else socket=ws;});
    }
    @PreDestroy void close(){if(socket!=null)socket.abort();}
    private class Listener implements WebSocket.Listener {
        private final StringBuilder text=new StringBuilder();
        public void onOpen(WebSocket ws){log.info("Binance live stream connected");ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){text.append(data);if(last)try{var n=json.readTree(text.toString()).get("data");var tick=new PriceTick(n.get("s").asText(),n.get("c").asText(),System.currentTimeMillis());latest.put(tick.pair(),tick);sink.tryEmitNext(tick);try{simulations.observe(tick.pair(),new java.math.BigDecimal(tick.price()),java.time.Instant.ofEpochMilli(tick.timestamp()));}catch(Exception e){log.warn("Mix simulation price check failed for {} without affecting live prices: {}",tick.pair(),e.getMessage());}}catch(Exception e){log.debug("Bad ticker message: {}",e.getMessage());}finally{text.setLength(0);}ws.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket ws,int code,String reason){socket=null;return null;}
        public void onError(WebSocket ws,Throwable error){log.warn("Binance live stream error: {}",error.getMessage());socket=null;}
    }
    public record PriceTick(String pair,String price,long timestamp){}
}
