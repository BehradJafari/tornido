package io.tornado.config;

import io.tornado.persistence.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

@Component
public class BootstrapData implements CommandLineRunner {
    private final CoinRepository coins; private final AppSettingsRepository settings; private final TornadoProperties props;private final AppUserRepository users;private final AuthProperties auth;private final PasswordEncoder encoder;
    public BootstrapData(CoinRepository c,AppSettingsRepository s,TornadoProperties p,AppUserRepository users,AuthProperties auth,PasswordEncoder encoder){coins=c;settings=s;props=p;this.users=users;this.auth=auth;this.encoder=encoder;}
    public void run(String... args){
        for(var c:props.defaultCoins()) if(coins.findBySymbolIgnoreCase(c.symbol()).isEmpty()) coins.save(new Coin(c.symbol(),c.pair()));
        settings.findById(1).orElseGet(()->settings.save(new AppSettings(props.scheduler().snapshotInterval().toSeconds(),props.scheduler().gradingHorizon().toSeconds())));
        users.findByUsernameIgnoreCase(auth.username()).orElseGet(()->users.save(new AppUser(auth.username(),encoder.encode(auth.password()),UserRole.ADMIN)));
    }
}
