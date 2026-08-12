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
    @Column(length=80) private String strategyCode;
    @Column(nullable=false) private int strategyVersion=1;
    private Instant signalAt;
    @Column(precision=30, scale=12) private BigDecimal signalPrice;
    @Column(nullable=false) private Instant predictedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=10) private Direction predictedDirection;
    @Column(nullable=false, precision=30, scale=12) private BigDecimal priceAtPrediction;
    @Column(nullable=false) private long horizonSeconds;
    private Instant targetAt;
    @Column(nullable=false) private int signalVersion=2;
    @Column(precision=30, scale=12) private BigDecimal priceAtGrading;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=12) private Outcome outcome=Outcome.PENDING;
    private Instant gradedAt;
    private Instant gradingPriceAt;
    private Long targetDelayMilliseconds;
    @Column(length=10) private String candleInterval;
    @Column(nullable=false) private int gradingAttempts;
    @Column(length=500) private String lastGradingError;
    protected Prediction() {}
    public Prediction(AnalysisRun run,Coin coin,String method,Instant at,Direction direction,BigDecimal price,Duration horizon){this(run,coin,method,1,method,at,price,at,price,direction,horizon,null);}
    public Prediction(AnalysisRun run,Coin coin,String strategyCode,int strategyVersion,String method,Instant signalAt,BigDecimal signalPrice,Instant executionAt,BigDecimal executionPrice,Direction direction,Duration horizon,String candleInterval){analysisRun=run;this.coin=coin;this.strategyCode=strategyCode;this.strategyVersion=strategyVersion;methodName=method;this.signalAt=signalAt;this.signalPrice=signalPrice;predictedAt=executionAt;predictedDirection=direction;priceAtPrediction=executionPrice;horizonSeconds=horizon.toSeconds();targetAt=executionAt.plus(horizon);this.candleInterval=candleInterval;}
    public void grade(BigDecimal price, Instant at, BigDecimal minimumMove){grade(price,at,at,minimumMove);}
    public void grade(BigDecimal price,Instant priceAt,Instant gradedAt,BigDecimal minimumMove){if(predictedDirection==Direction.NEUTRAL)throw new IllegalStateException("Neutral observations are not predictions and cannot be graded");gradingAttempts++;lastGradingError=null;priceAtGrading=price;gradingPriceAt=priceAt;this.gradedAt=gradedAt;if(targetAt==null)targetAt=predictedAt.plusSeconds(horizonSeconds);targetDelayMilliseconds=Duration.between(targetAt,priceAt).toMillis();BigDecimal change=price.subtract(priceAtPrediction).divide(priceAtPrediction,12,java.math.RoundingMode.HALF_UP);boolean correct=predictedDirection==Direction.UP?change.compareTo(minimumMove)>=0:change.compareTo(minimumMove.negate())<=0;outcome=correct?Outcome.CORRECT:Outcome.INCORRECT;}
    public void recordGradingError(String error){gradingAttempts++;lastGradingError=error==null?"Unknown grading error":error.substring(0,Math.min(500,error.length()));}
    public Long getId(){return id;} public AnalysisRun getAnalysisRun(){return analysisRun;} public Coin getCoin(){return coin;} public String getMethodName(){return methodName;}
    public String getStrategyCode(){return strategyCode;} public int getStrategyVersion(){return strategyVersion;} public Instant getSignalAt(){return signalAt;} public BigDecimal getSignalPrice(){return signalPrice;}
    public Instant getPredictedAt(){return predictedAt;} public Direction getPredictedDirection(){return predictedDirection;}
    public BigDecimal getPriceAtPrediction(){return priceAtPrediction;} public long getHorizonSeconds(){return horizonSeconds;}
    public Instant getTargetAt(){return targetAt;} public Instant getGradingPriceAt(){return gradingPriceAt;} public Long getTargetDelayMilliseconds(){return targetDelayMilliseconds;} public String getCandleInterval(){return candleInterval;}
    public int getGradingAttempts(){return gradingAttempts;} public String getLastGradingError(){return lastGradingError;}
    public int getSignalVersion(){return signalVersion;}
    public BigDecimal getPriceAtGrading(){return priceAtGrading;} public Outcome getOutcome(){return outcome;} public Instant getGradedAt(){return gradedAt;}
}
