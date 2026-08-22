package io.tornado.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Database-backed admission lock for one accepted live trade per coin and horizon.
 * TP and SL statuses always mean TP1 and SL1; deeper milestones remain simulation analytics only.
 */
@Entity
@Table(name = "active_signal_locks")
public class ActiveSignalLock {
    public enum Status { OPEN, CLOSED_TP, CLOSED_SL, CLOSED_TIMEOUT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "coin_id")
    private Coin coin;
    @Column(nullable = false)
    private long horizonSeconds;
    // Rankings are periodically replaced. ON DELETE SET NULL preserves the historical lock.
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "best_method_mix_id")
    private BestMethodMix bestMethodMix;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "mix_trade_simulation_id", unique = true)
    private MixTradeSimulation simulation;
    @Column(nullable = false)
    private Instant openedAt;
    @Column(nullable = false, precision = 30, scale = 12)
    private BigDecimal entryPrice;
    @Column(nullable = false)
    private Instant expectedCloseAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private Status status;
    private Instant closedAt;
    @Column(precision = 30, scale = 12)
    private BigDecimal closePrice;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected ActiveSignalLock() {}

    public ActiveSignalLock(Coin coin, BestMethodMix bestMethodMix, MixTradeSimulation simulation,
                            BigDecimal entryPrice, Instant openedAt) {
        this.coin = coin;
        this.horizonSeconds = bestMethodMix.getHorizonSeconds();
        this.bestMethodMix = bestMethodMix;
        this.simulation = simulation;
        this.openedAt = openedAt;
        this.entryPrice = entryPrice;
        this.expectedCloseAt = openedAt.plusSeconds(horizonSeconds);
        this.status = Status.OPEN;
        this.createdAt = openedAt;
        this.updatedAt = openedAt;
    }

    public boolean close(Status finalStatus, Instant at, BigDecimal price) {
        if (status != Status.OPEN) return false;
        if (finalStatus == Status.OPEN) throw new IllegalArgumentException("final lock status cannot be OPEN");
        status = finalStatus;
        closedAt = at;
        closePrice = price;
        updatedAt = at;
        return true;
    }

    public Long getId() { return id; }
    public Coin getCoin() { return coin; }
    public long getHorizonSeconds() { return horizonSeconds; }
    public BestMethodMix getBestMethodMix() { return bestMethodMix; }
    public MixTradeSimulation getSimulation() { return simulation; }
    public Instant getOpenedAt() { return openedAt; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public Instant getExpectedCloseAt() { return expectedCloseAt; }
    public Status getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
    public BigDecimal getClosePrice() { return closePrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
