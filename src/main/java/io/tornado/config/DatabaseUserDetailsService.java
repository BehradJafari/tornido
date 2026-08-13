package io.tornado.config;

import io.tornado.persistence.AppUserRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final AppUserRepository users;
    public DatabaseUserDetailsService(AppUserRepository users){this.users=users;}
    @Override public UserDetails loadUserByUsername(String username){var user=users.findByUsernameIgnoreCase(username).orElseThrow(()->new UsernameNotFoundException("user not found"));return User.withUsername(user.getUsername()).password(user.getPasswordHash()).roles(user.getRole().name()).disabled(!user.isEnabled()).build();}
}
