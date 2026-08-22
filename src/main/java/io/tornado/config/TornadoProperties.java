package io.tornado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("tornado")
public record TornadoProperties(Binance binance, Scheduler scheduler, List<DefaultCoin> defaultCoins) {
    public record Binance(String restBaseUrl, String websocketBaseUrl, String candleInterval, int candleLimit, Duration maximumRecoveryLookback) {
        public Binance {
            maximumRecoveryLookback = maximumRecoveryLookback == null ? Duration.ofHours(24) : maximumRecoveryLookback;
            if (maximumRecoveryLookback.isNegative() || maximumRecoveryLookback.isZero() || maximumRecoveryLookback.compareTo(Duration.ofDays(30)) > 0)
                throw new IllegalArgumentException("BINANCE_MAXIMUM_RECOVERY_LOOKBACK must be positive and at most 30 days");
        }
    }
    public record Scheduler(Duration snapshotInterval, Duration snapshotInitialDelay, Duration gradingInterval, Duration gradingHorizon) {}
    public record DefaultCoin(String symbol, String pair) {}
}
