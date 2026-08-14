package io.tornado;

import io.tornado.config.TornadoProperties;
import io.tornado.config.TelegramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({TornadoProperties.class,TelegramProperties.class})
public class TornadoApplication {
    public static void main(String[] args) { SpringApplication.run(TornadoApplication.class, args); }
}
