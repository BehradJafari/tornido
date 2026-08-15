package io.tornado.api;

import io.tornado.notification.TelegramNotificationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class TelegramSettingsControllerTest {
    @Test void sendsClearlyIdentifiedTestMessage() {
        var telegram=mock(TelegramNotificationService.class);
        when(telegram.send(contains("Tornido Telegram test"))).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.SENT,42L,null));
        var result=new TelegramSettingsController(telegram).testMessage();
        assertThat(result.status()).isEqualTo(TelegramNotificationService.DeliveryResult.Status.SENT);
        assertThat(result.messageId()).isEqualTo(42L);
        verify(telegram).send(contains("bot token and chat ID are configured correctly"));
    }

    @Test void exposesConfigurationFailureToPanel() {
        var telegram=mock(TelegramNotificationService.class);
        when(telegram.send(anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.SKIPPED,null,"Telegram environment configuration is incomplete"));
        var result=new TelegramSettingsController(telegram).testMessage();
        assertThat(result.status()).isEqualTo(TelegramNotificationService.DeliveryResult.Status.SKIPPED);
        assertThat(result.detail()).contains("incomplete");
    }
}
