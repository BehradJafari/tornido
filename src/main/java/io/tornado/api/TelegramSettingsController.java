package io.tornado.api;

import io.tornado.notification.TelegramNotificationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/settings/notifications")
public class TelegramSettingsController {
    private final TelegramNotificationService telegram;

    public TelegramSettingsController(TelegramNotificationService telegram) {
        this.telegram = telegram;
    }

    @PostMapping("/test")
    TestMessageResult testMessage() {
        var delivery = telegram.send("✅ Tornido Telegram test\n\nThe bot token and chat ID are configured correctly.\nSent from the Settings panel at " + Instant.now() + ".");
        return new TestMessageResult(delivery.status(), delivery.messageId(), delivery.detail());
    }

    public record TestMessageResult(TelegramNotificationService.DeliveryResult.Status status, Long messageId, String detail) {}
}
