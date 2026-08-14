package io.tornado.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
@Entity @Table(name="app_settings")
public class AppSettings {
    @Id private Integer id=1;
    @Column(nullable=false) private long snapshotIntervalSeconds;
    @Column(nullable=false) private long gradingHorizonSeconds;
    @Column(nullable=false) private boolean telegramNotificationsEnabled;
    @Column(nullable=false) private int minimumMixSimulationTrades=30;
    @Column(nullable=false,precision=8,scale=4) private java.math.BigDecimal mixTradeStopLossPercent=new java.math.BigDecimal("0.5");
    @Column(nullable=false) private boolean telegramDailyReportEnabled=true;
    @Column(nullable=false) private int minimumConfigurationSamples=100;
    @Column(nullable=false,precision=8,scale=4) private java.math.BigDecimal profileReplacementMinimumImprovementPercent=new java.math.BigDecimal("2.0");
    @Column(nullable=false) private int coinProfileMinimumSamples=250;
    @Column(nullable=false,precision=8,scale=4) private java.math.BigDecimal profileResearchRoundTripCostPercent=new java.math.BigDecimal("0.1");
    @Column(nullable=false) private int profileRefreshIntervalHours=24;
    @Column(nullable=false) private boolean automaticProfileResearchEnabled;
    protected AppSettings() {}
    public AppSettings(long snapshot,long horizon){snapshotIntervalSeconds=snapshot;gradingHorizonSeconds=horizon;}
    public long getSnapshotIntervalSeconds(){return snapshotIntervalSeconds;} public long getGradingHorizonSeconds(){return gradingHorizonSeconds;}
    @JsonIgnore public boolean isTelegramNotificationsEnabled(){return telegramNotificationsEnabled;}
    public int getMinimumMixSimulationTrades(){return minimumMixSimulationTrades;} public java.math.BigDecimal getMixTradeStopLossPercent(){return mixTradeStopLossPercent;} public boolean isTelegramDailyReportEnabled(){return telegramDailyReportEnabled;}
    public int getMinimumConfigurationSamples(){return minimumConfigurationSamples;} public java.math.BigDecimal getProfileReplacementMinimumImprovementPercent(){return profileReplacementMinimumImprovementPercent;} public int getCoinProfileMinimumSamples(){return coinProfileMinimumSamples;} public java.math.BigDecimal getProfileResearchRoundTripCostPercent(){return profileResearchRoundTripCostPercent;} public int getProfileRefreshIntervalHours(){return profileRefreshIntervalHours;} public boolean isAutomaticProfileResearchEnabled(){return automaticProfileResearchEnabled;}
    public void update(long snapshot,long horizon){snapshotIntervalSeconds=snapshot;gradingHorizonSeconds=horizon;}
    public void updateTelegram(boolean enabled){telegramNotificationsEnabled=enabled;}
    public void updateMixSignals(int minimumTrades,java.math.BigDecimal stopLossPercent,boolean dailyReportEnabled){if(minimumTrades<1||minimumTrades>100000)throw new IllegalArgumentException("minimum mix simulation trades must be between 1 and 100,000");if(stopLossPercent==null||stopLossPercent.compareTo(new java.math.BigDecimal("0.01"))<0||stopLossPercent.compareTo(new java.math.BigDecimal("20"))>0)throw new IllegalArgumentException("mix trade stop loss percent must be between 0.01 and 20");minimumMixSimulationTrades=minimumTrades;mixTradeStopLossPercent=stopLossPercent;telegramDailyReportEnabled=dailyReportEnabled;}
    public void updateProfileSelection(int minimum,int coinMinimum,java.math.BigDecimal replacement,java.math.BigDecimal cost,int refreshHours){updateProfileSelection(minimum,coinMinimum,replacement,cost,refreshHours,false);}public void updateProfileSelection(int minimum,int coinMinimum,java.math.BigDecimal replacement,java.math.BigDecimal cost,int refreshHours,boolean automatic){if(minimum<30||minimum>100000)throw new IllegalArgumentException("minimum configuration samples must be between 30 and 100,000");if(coinMinimum<minimum||coinMinimum>100000)throw new IllegalArgumentException("coin profile minimum must be at least the global minimum");if(replacement==null||replacement.compareTo(java.math.BigDecimal.ZERO)<0||replacement.compareTo(new java.math.BigDecimal("50"))>0)throw new IllegalArgumentException("replacement improvement must be between 0 and 50");if(cost==null||cost.compareTo(java.math.BigDecimal.ZERO)<0||cost.compareTo(new java.math.BigDecimal("10"))>0)throw new IllegalArgumentException("research cost must be between 0 and 10 percent");if(refreshHours<1||refreshHours>8760)throw new IllegalArgumentException("refresh interval must be between 1 and 8760 hours");minimumConfigurationSamples=minimum;coinProfileMinimumSamples=coinMinimum;profileReplacementMinimumImprovementPercent=replacement;profileResearchRoundTripCostPercent=cost;profileRefreshIntervalHours=refreshHours;automaticProfileResearchEnabled=automatic;}
}
