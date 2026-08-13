package io.tornado.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AppUserRepository extends JpaRepository<AppUser,Long>{Optional<AppUser> findByUsernameIgnoreCase(String username);List<AppUser> findAllByOrderByUsernameAsc();}
