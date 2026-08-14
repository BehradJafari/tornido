package io.tornado.config;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class AuthPropertiesTest{
 @Test void rejectsPublishedDevelopmentSecrets(){assertThatThrownBy(()->new AuthProperties("tornado-admin","local-development-password","local-development-jwt-secret-change-me",Duration.ofHours(12))).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsExamplePlaceholders(){assertThatThrownBy(()->new AuthProperties("admin","long-enough-password","CHANGE_ME_WITH_AT_LEAST_32_RANDOM_CHARACTERS",Duration.ofHours(12))).isInstanceOf(IllegalArgumentException.class);}
}
