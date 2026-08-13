package io.tornado.api;

import io.tornado.config.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.security.authentication.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authentication;private final JwtService jwt;
    public AuthController(AuthenticationManager authentication,JwtService jwt){this.authentication=authentication;this.jwt=jwt;}
    @PostMapping("/login") TokenResponse login(@Valid @RequestBody LoginRequest request,HttpServletResponse response){var auth=authentication.authenticate(new UsernamePasswordAuthenticationToken(request.username(),request.password()));String token=jwt.issue(auth);response.addHeader(HttpHeaders.SET_COOKIE,cookie(token,jwt.expiresInSeconds()).toString());return new TokenResponse(token,"Bearer",jwt.expiresInSeconds());}
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT) void logout(HttpServletResponse response){response.addHeader(HttpHeaders.SET_COOKIE,cookie("",0).toString());}
    @GetMapping("/me") UserResponse me(org.springframework.security.core.Authentication auth){boolean admin=auth.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN"));return new UserResponse(auth.getName(),admin?"ADMIN":"USER");}
    @ExceptionHandler(BadCredentialsException.class) ResponseEntity<java.util.Map<String,String>> badCredentials(){return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(java.util.Map.of("error","incorrect username or password"));}
    private ResponseCookie cookie(String value,long age){return ResponseCookie.from(JwtAuthenticationFilter.COOKIE_NAME,value).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(Duration.ofSeconds(age)).build();}
    public record LoginRequest(@NotBlank String username,@NotBlank String password){}
    public record TokenResponse(String accessToken,String tokenType,long expiresIn){}
    public record UserResponse(String username,String role){}
}
