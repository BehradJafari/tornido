package io.tornado.notification;

import io.tornado.config.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TelegramNotificationServiceTest {
    @Test void disabledConfigurationSkipsDeliveryWithoutCallingTelegram(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();

        var result=new TelegramNotificationService(new TelegramProperties("",""),builder).send("hello");

        assertThat(result.status()).isEqualTo(TelegramNotificationService.DeliveryResult.Status.SKIPPED);
        server.verify();
    }

    @Test void enabledConfigurationPostsMessageToConfiguredChat(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11/sendMessage")).andExpect(method(HttpMethod.POST)).andExpect(jsonPath("$.chat_id").value("@tornido")).andExpect(jsonPath("$.text").value("prediction ready")).andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":42}}",MediaType.APPLICATION_JSON));

        var result=new TelegramNotificationService(new TelegramProperties("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11","@tornido"),builder).send("prediction ready");

        assertThat(result.status()).isEqualTo(TelegramNotificationService.DeliveryResult.Status.SENT);
        assertThat(result.messageId()).isEqualTo(42);
        server.verify();
    }

    @Test void rateLimitResponseRetainsTelegramRetryAfterForAudit(){
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.telegram.org/bot123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11/sendMessage"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\",\"parameters\":{\"retry_after\":17}}"));

        var result=new TelegramNotificationService(new TelegramProperties("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11","@tornido"),builder).send("prediction ready");

        assertThat(result.status()).isEqualTo(TelegramNotificationService.DeliveryResult.Status.RATE_LIMITED);
        assertThat(result.detail()).contains("error_code=429","retry_after=17");
        server.verify();
    }
}
