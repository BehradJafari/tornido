package io.tornado.reporting;

import io.tornado.persistence.AppSettings;
import io.tornado.persistence.BestMethodMix;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class NotificationEligibilityPolicy {
    public enum SuppressionReason { STALE_BEST_MIX, WIN_RATE_BELOW_THRESHOLD, TELEGRAM_DISABLED, TELEGRAM_NOT_CONFIGURED }
    public record Decision(boolean eligible, SuppressionReason suppressionReason) {}

    private final BestMixRankingPolicy rankings;

    public NotificationEligibilityPolicy(BestMixRankingPolicy rankings) { this.rankings = rankings; }

    public Decision evaluate(BestMethodMix mix, AppSettings settings, boolean telegramConfigured) {
        if (!rankings.isCurrent(mix, settings.getTpSlLevels())) return new Decision(false, SuppressionReason.STALE_BEST_MIX);
        BigDecimal historical = BigDecimal.valueOf(mix.getTargetHitRate());
        if (historical.compareTo(settings.getMinimumNotificationWinRatePercent()) < 0)
            return new Decision(false, SuppressionReason.WIN_RATE_BELOW_THRESHOLD);
        if (!settings.isTelegramNotificationsEnabled()) return new Decision(false, SuppressionReason.TELEGRAM_DISABLED);
        if (!telegramConfigured) return new Decision(false, SuppressionReason.TELEGRAM_NOT_CONFIGURED);
        return new Decision(true, null);
    }
}
