package io.tornado.api;

import io.tornado.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.Duration;import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    @Test void loginReturnsBearerTokenAndSecureHttpOnlyCookie(){var manager=mock(AuthenticationManager.class);var auth=new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));when(manager.authenticate(any())).thenReturn(auth);var controller=new AuthController(manager,new JwtService(new AuthProperties("admin","long-enough-password","12345678901234567890123456789012",Duration.ofHours(1))));var response=new MockHttpServletResponse();var token=controller.login(new AuthController.LoginRequest("admin","password"),response);assertThat(token.tokenType()).isEqualTo("Bearer");assertThat(token.accessToken()).isNotBlank();assertThat(response.getHeader("Set-Cookie")).contains("tornado_token=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict");}
    @Test void jwtCanBeVerifiedAndRejectsTampering(){var service=new JwtService(new AuthProperties("admin","long-enough-password","12345678901234567890123456789012",Duration.ofHours(1)));var auth=new UsernamePasswordAuthenticationToken("admin",null);String token=service.issue(auth);assertThat(service.verify(token)).isEqualTo("admin");assertThat(service.verify(token+"broken")).isNull();}
}
