package io.tornado.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="coins")
public class Coin {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, unique=true, length=20) private String symbol;
    @Column(nullable=false, unique=true, length=30) private String pair;
    @Column(nullable=false) private boolean active = true;
    @Column(nullable=false) private Instant createdAt = Instant.now();
    protected Coin() {}
    public Coin(String symbol, String pair) { this.symbol=symbol; this.pair=pair; }
    public Long getId(){return id;} public String getSymbol(){return symbol;} public String getPair(){return pair;}
    public boolean isActive(){return active;} public Instant getCreatedAt(){return createdAt;}
    public void deactivate(){active=false;}
}
