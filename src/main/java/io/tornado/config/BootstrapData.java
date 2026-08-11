package io.tornado.config;

import io.tornado.persistence.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapData implements CommandLineRunner {
    private final CoinRepository coins; private final AppSettingsRepository settings; private final TornadoProperties props;
    public BootstrapData(CoinRepository c,AppSettingsRepository s,TornadoProperties p){coins=c;settings=s;props=p;}
    public void run(String... args){
        for(var c:props.defaultCoins()) if(coins.findBySymbolIgnoreCase(c.symbol()).isEmpty()) coins.save(new Coin(c.symbol(),c.pair()));
        settings.findById(1).orElseGet(()->settings.save(new AppSettings(props.scheduler().snapshotInterval().toSeconds(),props.scheduler().gradingHorizon().toSeconds())));
    }
}
