package io.tornado.config;

import io.tornado.persistence.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

@Component
public class BootstrapData implements CommandLineRunner {
    private final CoinRepository coins; private final AppSettingsRepository settings; private final TornadoProperties props;private final AppUserRepository users;private final AuthProperties auth;private final PasswordEncoder encoder;private final StrategyHorizonProfileRepository profiles;
    public BootstrapData(CoinRepository c,AppSettingsRepository s,TornadoProperties p,AppUserRepository users,AuthProperties auth,PasswordEncoder encoder,StrategyHorizonProfileRepository profiles){coins=c;settings=s;props=p;this.users=users;this.auth=auth;this.encoder=encoder;this.profiles=profiles;}
    public void run(String... args){
        var existing=coins.findAll().stream().map(c->c.getSymbol().toUpperCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        var missing=props.defaultCoins().stream().filter(c->!existing.contains(c.symbol().toUpperCase(java.util.Locale.ROOT))).map(c->new Coin(c.symbol(),c.pair())).toList();
        if(!missing.isEmpty())coins.saveAll(missing);
        settings.findById(1).orElseGet(()->settings.save(new AppSettings(props.scheduler().snapshotInterval().toSeconds(),props.scheduler().gradingHorizon().toSeconds())));
        users.findByUsernameIgnoreCase(auth.username()).orElseGet(()->users.save(new AppUser(auth.username(),encoder.encode(auth.password()),UserRole.ADMIN)));
        java.time.Instant now=java.time.Instant.now();for(var strategy:io.tornado.strategies.StrategyDefinition.values())for(long horizon:io.tornado.strategies.StrategyProfilePolicy.HORIZONS){String key=StrategyHorizonProfile.activeKey(ProfileScope.GLOBAL,null,strategy.code(),strategy.version(),horizon);if(profiles.findByActiveKey(key).isEmpty())profiles.save(StrategyHorizonProfile.fallback(strategy.code(),strategy.version(),horizon,io.tornado.strategies.StrategyProfilePolicy.fallback(horizon),strategy.defaultParameterKey(),now));}
    }
}
