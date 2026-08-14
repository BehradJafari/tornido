package io.tornado.notification;

import com.fasterxml.jackson.databind.JsonNode;
import io.tornado.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

@Service
public class TelegramNotificationService {
    private static final Logger log=LoggerFactory.getLogger(TelegramNotificationService.class);
    private final TelegramProperties properties;
    private final RestClient rest;

    public TelegramNotificationService(TelegramProperties properties,RestClient.Builder builder){this.properties=properties;rest=builder.baseUrl("https://api.telegram.org").build();}

    /** Reusable delivery entry point. Nothing invokes this until a notification event is added. */
    public DeliveryResult send(String message){
        if(message==null||message.isBlank())throw new IllegalArgumentException("Telegram message cannot be blank");
        if(message.length()>4096)throw new IllegalArgumentException("Telegram message cannot exceed 4096 characters");
        if(!properties.configured())return DeliveryResult.skipped("Telegram environment configuration is incomplete");
        try{
            JsonNode response=rest.post().uri(endpoint("sendMessage")).contentType(MediaType.APPLICATION_JSON).body(Map.of("chat_id",properties.chatId(),"text",message)).retrieve().body(JsonNode.class);
            if(response==null||!response.path("ok").asBoolean())return DeliveryResult.failed("Telegram rejected the message");
            return DeliveryResult.sent(response.path("result").path("message_id").asLong());
        }catch(RuntimeException error){log.warn("Telegram notification delivery failed: {}",error.getMessage());return DeliveryResult.failed("Telegram delivery failed");}
    }
    public DeliveryResult edit(long messageId,String message){if(!properties.configured())return DeliveryResult.skipped("Telegram environment configuration is incomplete");try{JsonNode response=rest.post().uri(endpoint("editMessageText")).contentType(MediaType.APPLICATION_JSON).body(Map.of("chat_id",properties.chatId(),"message_id",messageId,"text",message)).retrieve().body(JsonNode.class);return response!=null&&response.path("ok").asBoolean()?DeliveryResult.sent(messageId):DeliveryResult.failed("Telegram rejected the edit");}catch(RuntimeException error){log.warn("Telegram notification edit failed: {}",error.getMessage());return DeliveryResult.failed("Telegram edit failed");}}
    public boolean configured(){return properties.configured();}public String chatId(){return properties.chatId();}private URI endpoint(String method){String token=properties.botToken();if(token==null||!token.matches("\\d{5,20}:[A-Za-z0-9_-]{20,200}"))throw new IllegalStateException("Telegram bot token format is invalid");return URI.create("https://api.telegram.org/bot"+token+"/"+method);}

    public record DeliveryResult(Status status,Long messageId,String detail){
        public enum Status { SENT, SKIPPED, FAILED }
        static DeliveryResult sent(long messageId){return new DeliveryResult(Status.SENT,messageId,null);}
        static DeliveryResult skipped(String detail){return new DeliveryResult(Status.SKIPPED,null,detail);}
        static DeliveryResult failed(String detail){return new DeliveryResult(Status.FAILED,null,detail);}
    }
}
