package io.tornado.reporting;

import io.tornado.persistence.AppSettings;
import io.tornado.persistence.BestMethodMix;
import io.tornado.persistence.Coin;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEligibilityPolicyTest {
    private final NotificationEligibilityPolicy policy = new NotificationEligibilityPolicy(new BestMixRankingPolicy());

    @Test void rateBelowThresholdIsSuppressed() {
        BestMethodMix mix = mix(65);
        ReflectionTestUtils.setField(mix, "targetHitRate", 64.99d);
        var result = policy.evaluate(mix, settings("65"), true);
        assertThat(result.eligible()).isFalse();
        assertThat(result.suppressionReason()).isEqualTo(NotificationEligibilityPolicy.SuppressionReason.WIN_RATE_BELOW_THRESHOLD);
    }

    @Test void rateEqualToThresholdIsEligible() { assertThat(policy.evaluate(mix(65), settings("65"), true).eligible()).isTrue(); }
    @Test void rateAboveThresholdIsEligible() { assertThat(policy.evaluate(mix(70), settings("65"), true).eligible()).isTrue(); }

    private AppSettings settings(String threshold) {
        AppSettings settings = new AppSettings(900, 900);
        settings.updateTelegram(true);
        settings.updateMixSignals(1, settings.getTpSlLevels(), new BigDecimal(threshold), false);
        return settings;
    }

    private BestMethodMix mix(int hits) {
        return new BestMethodMix(new Coin("BTC", "BTCUSDT"), 900, 1, 1,
                List.of("RSI"), List.of(1), List.of("RSI"), 100, hits, hits, .6, 1,
                new BigDecimal("0.30"));
    }
}
