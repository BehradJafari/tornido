package io.tornado.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Entity @Table(name="predictions")
public class Prediction {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.EAGER, optional=false) @JoinColumn(name="coin_id") private Coin coin;
    @ManyToOne(fetch=FetchType.EAGER) @JoinColumn(name="analysis_run_id") private AnalysisRun analysisRun;
    @Column(nullable=false, length=100) private String methodName;
    @Column(nullable=false) private Instant predictedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=10) private Direction predictedDirection;
    @Column(nullable=false, precision=30, scale=12) private BigDecimal priceAtPrediction;
    @Column(nullable=false) private long horizonSeconds;
    @Column(nullable=false) private int signalVersion=2;
    @Column(precision=30, scale=12) private BigDecimal priceAtGrading;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=12) private Outcome outcome=Outcome.PENDING;
    private Instant gradedAt;
    protected Prediction() {}
    public Prediction(AnalysisRun run,Coin coin,String method,Instant at,Direction direction,BigDecimal price,Duration horizon){analysisRun=run;this.coin=coin;methodName=method;predictedAt=at;predictedDirection=direction;priceAtPrediction=price;horizonSeconds=horizon.toSeconds();}
    public void grade(BigDecimal price, Instant at, BigDecimal minimumMove){if(predictedDirection==Direction.NEUTRAL)throw new IllegalStateException("Neutral observations are not predictions and cannot be graded");priceAtGrading=price;gradedAt=at;BigDecimal change=price.subtract(priceAtPrediction).divide(priceAtPrediction,12,java.math.RoundingMode.HALF_UP);boolean correct=predictedDirection==Direction.UP?change.compareTo(minimumMove)>=0:change.compareTo(minimumMove.negate())<=0;outcome=correct?Outcome.CORRECT:Outcome.INCORRECT;}
    public Long getId(){return id;} public AnalysisRun getAnalysisRun(){return analysisRun;} public Coin getCoin(){return coin;} public String getMethodName(){return methodName;}
    public Instant getPredictedAt(){return predictedAt;} public Direction getPredictedDirection(){return predictedDirection;}
    public BigDecimal getPriceAtPrediction(){return priceAtPrediction;} public long getHorizonSeconds(){return horizonSeconds;}
    public int getSignalVersion(){return signalVersion;}
    public BigDecimal getPriceAtGrading(){return priceAtGrading;} public Outcome getOutcome(){return outcome;} public Instant getGradedAt(){return gradedAt;}
}
