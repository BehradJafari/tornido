package io.tornado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("tornado")
public record TornadoProperties(Binance binance, Scheduler scheduler, List<DefaultCoin> defaultCoins) {
    public record Binance(String restBaseUrl, String websocketBaseUrl, String candleInterval, int candleLimit) {}
    public record Scheduler(Duration snapshotInterval, Duration snapshotInitialDelay, Duration gradingInterval, Duration gradingHorizon) {}
    public record DefaultCoin(String symbol, String pair) {}
}
