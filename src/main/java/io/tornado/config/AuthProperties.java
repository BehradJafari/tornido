package io.tornado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("tornado.auth")
public record AuthProperties(String username,String password,String jwtSecret,Duration tokenTtl) {
    public AuthProperties {if(username==null||username.isBlank())throw new IllegalArgumentException("TORNADO_ADMIN_USERNAME is required");if(password==null||password.length()<12)throw new IllegalArgumentException("TORNADO_ADMIN_PASSWORD must contain at least 12 characters");if(jwtSecret==null||jwtSecret.length()<32)throw new IllegalArgumentException("TORNADO_JWT_SECRET must contain at least 32 characters");if(tokenTtl==null||tokenTtl.isNegative()||tokenTtl.isZero())throw new IllegalArgumentException("TORNADO_TOKEN_TTL must be positive");}
}
