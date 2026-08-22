package io.tornado.reporting;

import io.tornado.persistence.AppSettings;
import io.tornado.persistence.BestMethodMix;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class NotificationEligibilityPolicy {
    public record Decision(boolean eligible, SignalNotificationAuditReason suppressionReason) {}

    private final BestMixRankingPolicy rankings;

    public NotificationEligibilityPolicy(BestMixRankingPolicy rankings) { this.rankings = rankings; }

    public Decision evaluate(BestMethodMix mix, AppSettings settings, boolean telegramConfigured) {
        if (!rankings.isCurrent(mix, settings.getTpSlLevels())) return new Decision(false, SignalNotificationAuditReason.STALE_BEST_MIX);
        if (mix.getSamples() < settings.getMinimumMixSimulationTrades())
            return new Decision(false, SignalNotificationAuditReason.INSUFFICIENT_SAMPLES);
        BigDecimal historical = BigDecimal.valueOf(mix.getTargetHitRate());
        if (historical.compareTo(settings.getMinimumNotificationWinRatePercent()) < 0)
            return new Decision(false, SignalNotificationAuditReason.WIN_RATE_BELOW_THRESHOLD);
        if (!settings.isTelegramNotificationsEnabled()) return new Decision(false, SignalNotificationAuditReason.TELEGRAM_DISABLED);
        if (!telegramConfigured) return new Decision(false, SignalNotificationAuditReason.TELEGRAM_NOT_CONFIGURED);
        return new Decision(true, null);
    }
}
