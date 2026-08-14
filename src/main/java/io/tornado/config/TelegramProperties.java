package io.tornado.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("tornado.telegram")
public record TelegramProperties(String botToken,String chatId){public boolean configured(){return botToken!=null&&!botToken.isBlank()&&chatId!=null&&!chatId.isBlank();}}
