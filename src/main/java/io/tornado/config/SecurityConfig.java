package io.tornado.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.util.Map;

@Configuration @EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){return PasswordEncoderFactories.createDelegatingPasswordEncoder();}
    @Bean AuthenticationManager authenticationManager(UserDetailsService users,PasswordEncoder encoder){DaoAuthenticationProvider provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(encoder);return new ProviderManager(provider);}
    @Bean SecurityFilterChain security(HttpSecurity http,JwtAuthenticationFilter jwt,ObjectMapper mapper)throws Exception{return http.csrf(x->x.disable()).sessionManagement(x->x.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).httpBasic(x->x.disable()).formLogin(x->x.disable()).authorizeHttpRequests(x->x.requestMatchers("/api/auth/login","/api/auth/logout","/","/index.html","/assets/**","/favicon.ico").permitAll().requestMatchers("/api/users/**","/api/settings/notifications","/api/settings/mix-signals","/api/settings/profile-selection","/api/reports/best-mixes/rebuild","/api/reports/strategy-profiles/research").hasRole("ADMIN").requestMatchers("/api/**").authenticated().anyRequest().permitAll()).exceptionHandling(x->x.authenticationEntryPoint((request,response,error)->{response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);response.setContentType(MediaType.APPLICATION_JSON_VALUE);mapper.writeValue(response.getOutputStream(),Map.of("error","authentication required"));}).accessDeniedHandler((request,response,error)->{response.setStatus(HttpServletResponse.SC_FORBIDDEN);response.setContentType(MediaType.APPLICATION_JSON_VALUE);mapper.writeValue(response.getOutputStream(),Map.of("error","administrator access required"));})).addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class).build();}
}
