package io.tornado.api;

import io.tornado.persistence.*;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;import java.util.*;

@RestController @RequestMapping("/api/users")
public class UserController {
    private final AppUserRepository users;private final PasswordEncoder encoder;
    public UserController(AppUserRepository users,PasswordEncoder encoder){this.users=users;this.encoder=encoder;}
    @GetMapping List<UserDto> list(){return users.findAllByOrderByUsernameAsc().stream().map(UserDto::of).toList();}
    @PostMapping ResponseEntity<UserDto> create(@Valid @RequestBody CreateUser request){String username=request.username().trim().toLowerCase(Locale.ROOT);if(users.findByUsernameIgnoreCase(username).isPresent())throw new IllegalArgumentException("username already exists");return ResponseEntity.status(201).body(UserDto.of(users.save(new AppUser(username,encoder.encode(request.password()),request.role()))));}
    @PutMapping("/{id}/password") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional void password(@PathVariable long id,@Valid @RequestBody PasswordRequest request){users.findById(id).orElseThrow().updatePassword(encoder.encode(request.password()));}
    @PutMapping("/{id}/enabled") UserDto enabled(@PathVariable long id,@RequestBody EnabledRequest request,Authentication auth){var user=users.findById(id).orElseThrow();if(user.getUsername().equalsIgnoreCase(auth.getName())&&!request.enabled())throw new IllegalArgumentException("you cannot disable your own account");user.setEnabled(request.enabled());return UserDto.of(users.save(user));}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable long id,Authentication auth){var user=users.findById(id).orElseThrow();if(user.getUsername().equalsIgnoreCase(auth.getName()))throw new IllegalArgumentException("you cannot delete your own account");users.delete(user);}
    public record CreateUser(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{3,80}") String username,@NotBlank @Size(min=12,max=200) String password,@NotNull UserRole role){}
    public record PasswordRequest(@NotBlank @Size(min=12,max=200) String password){}
    public record EnabledRequest(boolean enabled){}
    public record UserDto(long id,String username,UserRole role,boolean enabled,Instant createdAt){static UserDto of(AppUser u){return new UserDto(u.getId(),u.getUsername(),u.getRole(),u.isEnabled(),u.getCreatedAt());}}
}
