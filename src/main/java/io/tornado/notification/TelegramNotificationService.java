package io.tornado.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tornado.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Map;

@Service
public class TelegramNotificationService {
    private static final Logger log=LoggerFactory.getLogger(TelegramNotificationService.class);
    private static final ObjectMapper JSON=new ObjectMapper();
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
            if(response==null||!response.path("ok").asBoolean())return DeliveryResult.failed(apiError(response,"Telegram rejected the message"));
            return DeliveryResult.sent(response.path("result").path("message_id").asLong());
        }catch(RestClientResponseException error){
            String detail=responseError(error);
            if(error.getStatusCode().value()==429){log.warn("SIGNAL_NOTIFY_AUDIT telegram-api reason=TELEGRAM_RATE_LIMITED {}",detail);return DeliveryResult.rateLimited(detail);}
            log.warn("SIGNAL_NOTIFY_AUDIT telegram-api reason=TELEGRAM_FAILED status={} detail={}",error.getStatusCode().value(),detail);return DeliveryResult.failed(detail);
        }catch(RuntimeException error){log.warn("SIGNAL_NOTIFY_AUDIT telegram-api reason=TELEGRAM_FAILED detail={}",error.getMessage());return DeliveryResult.failed("Telegram delivery failed: "+safe(error.getMessage()));}
    }
    public DeliveryResult edit(long messageId,String message){if(!properties.configured())return DeliveryResult.skipped("Telegram environment configuration is incomplete");try{JsonNode response=rest.post().uri(endpoint("editMessageText")).contentType(MediaType.APPLICATION_JSON).body(Map.of("chat_id",properties.chatId(),"message_id",messageId,"text",message)).retrieve().body(JsonNode.class);return response!=null&&response.path("ok").asBoolean()?DeliveryResult.sent(messageId):DeliveryResult.failed("Telegram rejected the edit");}catch(RuntimeException error){log.warn("Telegram notification edit failed: {}",error.getMessage());return DeliveryResult.failed("Telegram edit failed");}}
    public boolean configured(){return properties.configured();}public String chatId(){return properties.chatId();}private URI endpoint(String method){String token=properties.botToken();if(token==null||!token.matches("\\d{5,20}:[A-Za-z0-9_-]{20,200}"))throw new IllegalStateException("Telegram bot token format is invalid");return URI.create("https://api.telegram.org/bot"+token+"/"+method);}

    private String responseError(RestClientResponseException error){try{JsonNode body=JSON.readTree(error.getResponseBodyAsString());return "status="+error.getStatusCode().value()+" "+apiError(body,"Telegram request failed");}catch(Exception ignored){return "status="+error.getStatusCode().value()+" "+safe(error.getStatusText());}}
    private String apiError(JsonNode response,String fallback){if(response==null)return fallback;String description=response.path("description").asText(fallback);int code=response.path("error_code").asInt(0);int retry=response.path("parameters").path("retry_after").asInt(0);return "ok="+response.path("ok").asBoolean(false)+(code==0?"":" error_code="+code)+" description="+safe(description)+(retry==0?"":" retry_after="+retry);}
    private String safe(String value){if(value==null||value.isBlank())return "unavailable";String cleaned=value.replaceAll("[\\r\\n]+"," ");return cleaned.substring(0,Math.min(300,cleaned.length()));}

    public record DeliveryResult(Status status,Long messageId,String detail){
        public enum Status { SENT, SKIPPED, FAILED, RATE_LIMITED }
        static DeliveryResult sent(long messageId){return new DeliveryResult(Status.SENT,messageId,null);}
        static DeliveryResult skipped(String detail){return new DeliveryResult(Status.SKIPPED,null,detail);}
        static DeliveryResult failed(String detail){return new DeliveryResult(Status.FAILED,null,detail);}
        static DeliveryResult rateLimited(String detail){return new DeliveryResult(Status.RATE_LIMITED,null,detail);}
    }
}
