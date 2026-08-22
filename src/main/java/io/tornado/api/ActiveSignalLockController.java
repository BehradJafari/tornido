package io.tornado.api;

import io.tornado.persistence.ActiveSignalLock;
import io.tornado.persistence.Direction;
import io.tornado.persistence.MixTradeSimulation;
import io.tornado.reporting.ActiveSignalLockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/active-signal-locks")
public class ActiveSignalLockController {
    private final ActiveSignalLockService locks;

    public ActiveSignalLockController(ActiveSignalLockService locks) {
        this.locks = locks;
    }

    @GetMapping("/open")
    OpenLocksResponse open() {
        Instant serverNow = Instant.now();
        List<OpenLockDto> items = locks.openLocks().stream()
                .map(lock -> OpenLockDto.from(lock, serverNow))
                .toList();
        return new OpenLocksResponse(serverNow, items);
    }

    public record OpenLocksResponse(Instant serverNow, List<OpenLockDto> items) {}

    public record OpenLockDto(
            long id,
            String coin,
            String pair,
            Direction direction,
            long horizonSeconds,
            Long bestMixId,
            long mixTradeSimulationId,
            Instant openedAt,
            Instant expectedCloseAt,
            BigDecimal entryPrice,
            BigDecimal tp1Price,
            BigDecimal sl1Price,
            long elapsedSeconds,
            long remainingSeconds,
            double historicalWinRate,
            double historicalWilsonScore,
            List<String> methods,
            boolean telegramSent,
            Long telegramMessageId,
            MixTradeSimulation.NotificationDeliveryStatus notificationDeliveryStatus
    ) {
        static OpenLockDto from(ActiveSignalLock lock, Instant serverNow) {
            MixTradeSimulation simulation = lock.getSimulation();
            long elapsed = Math.max(0, Duration.between(lock.getOpenedAt(), serverNow).toSeconds());
            long remaining = Math.max(0, Duration.between(serverNow, lock.getExpectedCloseAt()).toSeconds());
            return new OpenLockDto(
                    lock.getId(), lock.getCoin().getSymbol(), lock.getCoin().getPair(),
                    simulation.getDirection(), lock.getHorizonSeconds(),
                    lock.getBestMethodMix() == null ? null : lock.getBestMethodMix().getId(),
                    simulation.getId(), lock.getOpenedAt(), lock.getExpectedCloseAt(),
                    lock.getEntryPrice(), simulation.getTp1Price(), simulation.getSl1Price(),
                    elapsed, remaining, simulation.getHistoricalTargetHitRate(),
                    simulation.getHistoricalWilsonScore(), simulation.getMethodNames(),
                    simulation.isTelegramSent(), simulation.getTelegramMessageId(),
                    simulation.getNotificationDeliveryStatus());
        }
    }
}
