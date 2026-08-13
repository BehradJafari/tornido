package io.tornado.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="application_users")
public class AppUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,unique=true,length=80) private String username;
    @Column(nullable=false,length=255) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=12) private UserRole role;
    @Column(nullable=false) private boolean enabled=true;
    @Column(nullable=false) private Instant createdAt=Instant.now();
    protected AppUser(){}
    public AppUser(String username,String passwordHash,UserRole role){this.username=username;this.passwordHash=passwordHash;this.role=role;}
    public void updatePassword(String passwordHash){this.passwordHash=passwordHash;}
    public void setEnabled(boolean enabled){this.enabled=enabled;}
    public Long getId(){return id;}public String getUsername(){return username;}public String getPasswordHash(){return passwordHash;}public UserRole getRole(){return role;}public boolean isEnabled(){return enabled;}public Instant getCreatedAt(){return createdAt;}
}
