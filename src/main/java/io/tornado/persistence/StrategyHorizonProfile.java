package io.tornado.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="strategy_horizon_profiles")
public class StrategyHorizonProfile {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=80) private String strategyCode;
    @Column(nullable=false) private int strategyVersion;
    @Column(nullable=false) private long predictionHorizonSeconds;
    @Column(nullable=false,length=10) private String analysisTimeframe;
    @Column(nullable=false,length=120) private String parameterKey;
    @Column(nullable=false) private int profileVersion;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private ProfileScope profileScope;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="coin_id") @JsonIgnore private Coin coin;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private ProfileSelectionSource selectionSource;
    private long trainingSamples; private long validationSamples; private long testSamples;
    private double targetHitRate; private double directionalAccuracy; private double wilsonTargetScore; private double wilsonDirectionalScore;
    private double profitableTradeRate; private double averageMarketReturn; private double medianMarketReturn; private double netPnlPercent; private double maximumDrawdownPercent;
    private int walkForwardWindows; private int walkForwardPositiveWindows; private double walkForwardConsistency; private double selectionScore;
    @Column(nullable=false) private boolean active=true;
    @Column(unique=true,length=220) private String activeKey;
    @Column(nullable=false) private Instant selectedAt;
    private Instant supersededAt;
    @Column(length=500) private String replacementReason;
    @Column(nullable=false) private Instant createdAt;
    protected StrategyHorizonProfile() {}
    public static StrategyHorizonProfile fallback(String strategyCode,int strategyVersion,long horizon,String timeframe,String parameterKey,Instant now){return create(null,ProfileScope.GLOBAL,strategyCode,strategyVersion,horizon,timeframe,parameterKey,1,ProfileSelectionSource.FALLBACK,new Evidence(),"Deterministic fallback; insufficient validated history",now);}
    public static StrategyHorizonProfile selected(Coin coin,ProfileScope scope,String strategyCode,int strategyVersion,long horizon,String timeframe,String parameterKey,int version,Evidence evidence,String reason,Instant now){return create(coin,scope,strategyCode,strategyVersion,horizon,timeframe,parameterKey,version,ProfileSelectionSource.WALK_FORWARD_VALIDATION,evidence,reason,now);}
    private static StrategyHorizonProfile create(Coin coin,ProfileScope scope,String code,int strategyVersion,long horizon,String timeframe,String parameters,int version,ProfileSelectionSource source,Evidence e,String reason,Instant now){StrategyHorizonProfile p=new StrategyHorizonProfile();p.coin=coin;p.profileScope=scope;p.strategyCode=code;p.strategyVersion=strategyVersion;p.predictionHorizonSeconds=horizon;p.analysisTimeframe=timeframe;p.parameterKey=parameters;p.profileVersion=version;p.selectionSource=source;p.trainingSamples=e.trainingSamples();p.validationSamples=e.validationSamples();p.testSamples=e.testSamples();p.targetHitRate=e.targetHitRate();p.directionalAccuracy=e.directionalAccuracy();p.wilsonTargetScore=e.wilsonTargetScore();p.wilsonDirectionalScore=e.wilsonDirectionalScore();p.profitableTradeRate=e.profitableTradeRate();p.averageMarketReturn=e.averageMarketReturn();p.medianMarketReturn=e.medianMarketReturn();p.netPnlPercent=e.netPnlPercent();p.maximumDrawdownPercent=e.maximumDrawdownPercent();p.walkForwardWindows=e.walkForwardWindows();p.walkForwardPositiveWindows=e.walkForwardPositiveWindows();p.walkForwardConsistency=e.walkForwardConsistency();p.selectionScore=e.selectionScore();p.replacementReason=reason;p.selectedAt=now;p.createdAt=now;p.activeKey=activeKey(scope,coin,code,strategyVersion,horizon);return p;}
    public void supersede(Instant at,String reason){active=false;activeKey=null;supersededAt=at;replacementReason=reason;}
    public static String activeKey(ProfileScope scope,Coin coin,String code,int strategyVersion,long horizon){return scope+":"+(coin==null?"GLOBAL":coin.getId())+":"+code+"@"+strategyVersion+":"+horizon;}
    public Long getId(){return id;} public String getStrategyCode(){return strategyCode;} public int getStrategyVersion(){return strategyVersion;} public long getPredictionHorizonSeconds(){return predictionHorizonSeconds;} public String getAnalysisTimeframe(){return analysisTimeframe;} public String getParameterKey(){return parameterKey;} public int getProfileVersion(){return profileVersion;} public ProfileScope getProfileScope(){return profileScope;} public Long getCoinId(){return coin==null?null:coin.getId();} public String getCoinSymbol(){return coin==null?null:coin.getSymbol();} public ProfileSelectionSource getSelectionSource(){return selectionSource;} public long getTrainingSamples(){return trainingSamples;} public long getValidationSamples(){return validationSamples;} public long getTestSamples(){return testSamples;} public double getTargetHitRate(){return targetHitRate;} public double getDirectionalAccuracy(){return directionalAccuracy;} public double getWilsonTargetScore(){return wilsonTargetScore;} public double getWilsonDirectionalScore(){return wilsonDirectionalScore;} public double getProfitableTradeRate(){return profitableTradeRate;} public double getAverageMarketReturn(){return averageMarketReturn;} public double getMedianMarketReturn(){return medianMarketReturn;} public double getNetPnlPercent(){return netPnlPercent;} public double getMaximumDrawdownPercent(){return maximumDrawdownPercent;} public int getWalkForwardWindows(){return walkForwardWindows;} public int getWalkForwardPositiveWindows(){return walkForwardPositiveWindows;} public double getWalkForwardConsistency(){return walkForwardConsistency;} public double getSelectionScore(){return selectionScore;} public boolean isActive(){return active;} public Instant getSelectedAt(){return selectedAt;} public Instant getSupersededAt(){return supersededAt;} public String getReplacementReason(){return replacementReason;} public Instant getCreatedAt(){return createdAt;}
    public record Evidence(long trainingSamples,long validationSamples,long testSamples,double targetHitRate,double directionalAccuracy,double wilsonTargetScore,double wilsonDirectionalScore,double profitableTradeRate,double averageMarketReturn,double medianMarketReturn,double netPnlPercent,double maximumDrawdownPercent,int walkForwardWindows,int walkForwardPositiveWindows,double walkForwardConsistency,double selectionScore){public Evidence(){this(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);}}
}
