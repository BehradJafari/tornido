package io.tornado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("tornado.auth")
public record AuthProperties(String username,String password,String jwtSecret,Duration tokenTtl) {
    public AuthProperties {if(username==null||username.isBlank())throw new IllegalArgumentException("TORNADO_ADMIN_USERNAME is required");if(password==null||password.length()<12||password.equals("local-development-password")||password.equals("CHANGE_ME"))throw new IllegalArgumentException("TORNADO_ADMIN_PASSWORD must contain at least 12 non-default characters");if(jwtSecret==null||jwtSecret.length()<32||jwtSecret.equals("local-development-jwt-secret-change-me")||jwtSecret.startsWith("CHANGE_ME"))throw new IllegalArgumentException("TORNADO_JWT_SECRET must contain at least 32 non-default characters");if(tokenTtl==null||tokenTtl.isNegative()||tokenTtl.isZero())throw new IllegalArgumentException("TORNADO_TOKEN_TTL must be positive");}
}
