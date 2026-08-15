package io.tornado.persistence;

import jakarta.persistence.*;
import java.math.*;
import java.time.*;
import java.util.*;

@Entity @Table(name="mix_trade_simulations")
public class MixTradeSimulation {
    /** TARGET_HIT/STOP_LOSS_HIT are retained so historic rows remain readable. */
    public enum Status { OPEN, TP3_HIT, SL3_HIT, TARGET_HIT, STOP_LOSS_HIT, CANCELLED, ERROR }
    public enum Milestone { TP1, TP2, TP3, SL1, SL2, SL3 }
    public enum NotificationDeliveryStatus { NOT_ATTEMPTED, SENT, SKIPPED, FAILED, LEGACY }
    public record Observation(boolean changed, List<Milestone> milestones, boolean terminal) {
        public static Observation none() { return new Observation(false, List.of(), false); }
    }

    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="coin_id") private Coin coin;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="best_mix_id") private BestMethodMix bestMix;
    private long horizonSeconds; private int mixSize; private int mixRank;
    @Column(length=2000) private String methodNames; @Column(length=1000) private String strategyCodes; @Column(length=1000) private String strategyVersions;
    @Enumerated(EnumType.STRING) @Column(length=8) private Direction direction;
    private int agreementCount; private int totalMethods; private long historicalSamples;
    private double historicalTargetHitRate; private double historicalDirectionalAccuracy; private double historicalWilsonScore;
    private Instant openedAt; @Column(precision=30,scale=12) private BigDecimal entryPrice;
    /** Legacy columns. New rows point these at terminal TP3/SL3. */
    @Column(precision=30,scale=12) private BigDecimal targetPrice; @Column(precision=30,scale=12) private BigDecimal stopLossPrice;
    @Column(name="tp1_percent",precision=8,scale=4) private BigDecimal tp1Percent; @Column(name="tp2_percent",precision=8,scale=4) private BigDecimal tp2Percent; @Column(name="tp3_percent",precision=8,scale=4) private BigDecimal tp3Percent;
    @Column(name="sl1_percent",precision=8,scale=4) private BigDecimal sl1Percent; @Column(name="sl2_percent",precision=8,scale=4) private BigDecimal sl2Percent; @Column(name="sl3_percent",precision=8,scale=4) private BigDecimal sl3Percent;
    @Column(name="tp1_price",precision=30,scale=12) private BigDecimal tp1Price; @Column(name="tp2_price",precision=30,scale=12) private BigDecimal tp2Price; @Column(name="tp3_price",precision=30,scale=12) private BigDecimal tp3Price;
    @Column(name="sl1_price",precision=30,scale=12) private BigDecimal sl1Price; @Column(name="sl2_price",precision=30,scale=12) private BigDecimal sl2Price; @Column(name="sl3_price",precision=30,scale=12) private BigDecimal sl3Price;
    @Column(name="tp1_hit_at") private Instant tp1HitAt; @Column(name="tp2_hit_at") private Instant tp2HitAt; @Column(name="tp3_hit_at") private Instant tp3HitAt; @Column(name="sl1_hit_at") private Instant sl1HitAt; @Column(name="sl2_hit_at") private Instant sl2HitAt; @Column(name="sl3_hit_at") private Instant sl3HitAt;
    @Enumerated(EnumType.STRING) @Column(length=24) private Status status;
    private Instant closedAt; @Column(precision=30,scale=12) private BigDecimal closePrice;
    @Column(precision=30,scale=12) private BigDecimal lastCheckedPrice; @Column(nullable=false) private Instant lastCheckedAt;
    private Double resultPercent; private Long telegramMessageId; @Column(unique=true,length=300) private String activeKey;
    @Column(nullable=false) private int signalVersion=3;
    @Column(name="ranking_tp_level",nullable=false) private int rankingTpLevel=1;
    @Column(name="ranking_target_percent",nullable=false,precision=8,scale=4) private BigDecimal rankingTargetPercent=new BigDecimal("0.30");
    @Column(name="minimum_notification_win_rate_percent",nullable=false,precision=8,scale=4) private BigDecimal minimumNotificationWinRatePercent=new BigDecimal("60.00");
    @Column(name="eligible_for_notification",nullable=false) private boolean eligibleForNotification;
    @Column(name="notification_suppression_reason",length=64) private String notificationSuppressionReason;
    @Column(name="telegram_sent",nullable=false) private boolean telegramSent;
    @Enumerated(EnumType.STRING) @Column(name="notification_delivery_status",nullable=false,length=16) private NotificationDeliveryStatus notificationDeliveryStatus=NotificationDeliveryStatus.NOT_ATTEMPTED;
    @Column(name="notification_error",length=500) private String notificationError;
    private Instant createdAt; private Instant updatedAt;

    protected MixTradeSimulation() {}
    public MixTradeSimulation(Coin coin,BestMethodMix mix,Direction direction,int agreement,BigDecimal entry,TpSlLevels levels,Instant opened) {
        this.coin=coin;bestMix=mix;horizonSeconds=mix.getHorizonSeconds();mixSize=mix.getMixSize();mixRank=mix.getRank();
        methodNames=String.join("||",mix.getMethodNames());strategyCodes=String.join("||",mix.getStrategyCodes());strategyVersions=mix.getStrategyVersions().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("||"));this.direction=direction;
        agreementCount=agreement;totalMethods=mixSize;historicalSamples=mix.getSamples();historicalTargetHitRate=mix.getTargetHitRate();historicalDirectionalAccuracy=mix.getDirectionalAccuracy();historicalWilsonScore=mix.getWilsonScore();
        openedAt=opened;entryPrice=entry;tp1Percent=levels.tp1();tp2Percent=levels.tp2();tp3Percent=levels.tp3();sl1Percent=levels.sl1();sl2Percent=levels.sl2();sl3Percent=levels.sl3();
        tp1Price=levels.price(entry,direction,true,1);tp2Price=levels.price(entry,direction,true,2);tp3Price=levels.price(entry,direction,true,3);
        sl1Price=levels.price(entry,direction,false,1);sl2Price=levels.price(entry,direction,false,2);sl3Price=levels.price(entry,direction,false,3);
        targetPrice=tp3Price;stopLossPrice=sl3Price;status=Status.OPEN;lastCheckedPrice=entry;lastCheckedAt=opened;
        signalVersion=mix.getSignalVersion();rankingTpLevel=mix.getTpLevel();rankingTargetPercent=mix.getTargetPercent();eligibleForNotification=true;
        activeKey=activeKey(coin,mix,direction);createdAt=opened;updatedAt=opened;
    }
    public MixTradeSimulation(Coin coin,BestMethodMix mix,Direction direction,int agreement,BigDecimal entry,TpSlLevels levels,Instant opened,BigDecimal minimumNotificationWinRatePercent,boolean eligibleForNotification,String suppressionReason) { this(coin,mix,direction,agreement,entry,levels,opened);this.minimumNotificationWinRatePercent=minimumNotificationWinRatePercent;this.eligibleForNotification=eligibleForNotification;this.notificationSuppressionReason=suppressionReason; }
    /** Compatibility constructor for callers compiled against the old API. */
    public MixTradeSimulation(Coin coin,BestMethodMix mix,Direction direction,int agreement,BigDecimal entry,BigDecimal ignoredLegacyStop,Instant opened) { this(coin,mix,direction,agreement,entry,TpSlLevels.defaults(),opened); }

    public static String activeKey(Coin coin,BestMethodMix mix,Direction direction) { List<String>strategies=new ArrayList<>();List<String>codes=mix.getStrategyCodes();List<Integer>versions=mix.getStrategyVersions();for(int i=0;i<codes.size();i++)strategies.add(codes.get(i)+"@"+versions.get(i));strategies.sort(Comparator.naturalOrder());return coin.getId()+":"+mix.getHorizonSeconds()+":"+String.join(",",strategies)+":"+direction; }
    public void telegramMessage(Long id){telegramMessageId=id;telegramSent=id!=null;notificationDeliveryStatus=id==null?NotificationDeliveryStatus.FAILED:NotificationDeliveryStatus.SENT;notificationError=null;updatedAt=Instant.now();}
    public void notificationDelivery(NotificationDeliveryStatus status,String detail){notificationDeliveryStatus=status;notificationError=detail==null?null:detail.substring(0,Math.min(500,detail.length()));telegramSent=status==NotificationDeliveryStatus.SENT;updatedAt=Instant.now();}

    public Observation observeMilestones(BigDecimal price,Instant at) {
        if(status!=Status.OPEN||at.isBefore(lastCheckedAt)) return Observation.none();
        lastCheckedPrice=price;lastCheckedAt=at;updatedAt=at;List<Milestone> hit=new ArrayList<>();
        if(tp1HitAt==null&&crossed(price,tp1Price,true)){tp1HitAt=at;hit.add(Milestone.TP1);}if(tp2HitAt==null&&crossed(price,tp2Price,true)){tp2HitAt=at;hit.add(Milestone.TP2);}if(tp3HitAt==null&&crossed(price,tp3Price,true)){tp3HitAt=at;hit.add(Milestone.TP3);}
        if(sl1HitAt==null&&crossed(price,sl1Price,false)){sl1HitAt=at;hit.add(Milestone.SL1);}if(sl2HitAt==null&&crossed(price,sl2Price,false)){sl2HitAt=at;hit.add(Milestone.SL2);}if(sl3HitAt==null&&crossed(price,sl3Price,false)){sl3HitAt=at;hit.add(Milestone.SL3);}
        boolean terminal=hit.contains(Milestone.TP3)||hit.contains(Milestone.SL3);if(terminal){status=hit.contains(Milestone.TP3)?Status.TP3_HIT:Status.SL3_HIT;closedAt=at;closePrice=price;resultPercent=price.subtract(entryPrice).divide(entryPrice,12,RoundingMode.HALF_UP).doubleValue()*100*(direction==Direction.UP?1:-1);activeKey=null;}
        return new Observation(!hit.isEmpty(),List.copyOf(hit),terminal);
    }
    public boolean observe(BigDecimal price,Instant at){return observeMilestones(price,at).changed();}
    private boolean crossed(BigDecimal price,BigDecimal threshold,boolean takeProfit){if(threshold==null)return false;return direction==Direction.UP?(takeProfit?price.compareTo(threshold)>=0:price.compareTo(threshold)<=0):(takeProfit?price.compareTo(threshold)<=0:price.compareTo(threshold)>=0);}
    public boolean isTerminal(){return status!=Status.OPEN;}
    public List<Integer> getStrategyVersions(){return split(strategyVersions).stream().filter(s->!s.isBlank()).map(Integer::valueOf).toList();}

    public Long getId(){return id;}public Coin getCoin(){return coin;}public BestMethodMix getBestMix(){return bestMix;}public long getHorizonSeconds(){return horizonSeconds;}public int getMixSize(){return mixSize;}public int getMixRank(){return mixRank;}public List<String>getMethodNames(){return split(methodNames);}public List<String>getStrategyCodes(){return split(strategyCodes);}public Direction getDirection(){return direction;}public int getAgreementCount(){return agreementCount;}public int getTotalMethods(){return totalMethods;}public long getHistoricalSamples(){return historicalSamples;}public double getHistoricalTargetHitRate(){return historicalTargetHitRate;}public double getHistoricalDirectionalAccuracy(){return historicalDirectionalAccuracy;}public double getHistoricalWilsonScore(){return historicalWilsonScore;}public Instant getOpenedAt(){return openedAt;}public BigDecimal getEntryPrice(){return entryPrice;}public BigDecimal getTargetPrice(){return targetPrice;}public BigDecimal getStopLossPrice(){return stopLossPrice;}public BigDecimal getTp1Percent(){return tp1Percent;}public BigDecimal getTp2Percent(){return tp2Percent;}public BigDecimal getTp3Percent(){return tp3Percent;}public BigDecimal getSl1Percent(){return sl1Percent;}public BigDecimal getSl2Percent(){return sl2Percent;}public BigDecimal getSl3Percent(){return sl3Percent;}public BigDecimal getTp1Price(){return tp1Price;}public BigDecimal getTp2Price(){return tp2Price;}public BigDecimal getTp3Price(){return tp3Price;}public BigDecimal getSl1Price(){return sl1Price;}public BigDecimal getSl2Price(){return sl2Price;}public BigDecimal getSl3Price(){return sl3Price;}public Instant getTp1HitAt(){return tp1HitAt;}public Instant getTp2HitAt(){return tp2HitAt;}public Instant getTp3HitAt(){return tp3HitAt;}public Instant getSl1HitAt(){return sl1HitAt;}public Instant getSl2HitAt(){return sl2HitAt;}public Instant getSl3HitAt(){return sl3HitAt;}public Instant tpHitAt(int level){return level==1?tp1HitAt:level==2?tp2HitAt:level==3?tp3HitAt:invalidLevel(level);}public Instant slHitAt(int level){return level==1?sl1HitAt:level==2?sl2HitAt:level==3?sl3HitAt:invalidLevel(level);}public BigDecimal tpPercent(int level){return level==1?tp1Percent:level==2?tp2Percent:level==3?tp3Percent:invalidLevel(level);}public BigDecimal slPercent(int level){return level==1?sl1Percent:level==2?sl2Percent:level==3?sl3Percent:invalidLevel(level);}public BigDecimal tpPrice(int level){return level==1?tp1Price:level==2?tp2Price:level==3?tp3Price:invalidLevel(level);}public BigDecimal slPrice(int level){return level==1?sl1Price:level==2?sl2Price:level==3?sl3Price:invalidLevel(level);}public Status getStatus(){return status;}public Instant getClosedAt(){return closedAt;}public BigDecimal getClosePrice(){return closePrice;}public BigDecimal getLastCheckedPrice(){return lastCheckedPrice;}public Instant getLastCheckedAt(){return lastCheckedAt;}public Double getResultPercent(){return resultPercent;}public Long getTelegramMessageId(){return telegramMessageId;}public int getSignalVersion(){return signalVersion;}public int getRankingTpLevel(){return rankingTpLevel;}public BigDecimal getRankingTargetPercent(){return rankingTargetPercent;}public BigDecimal getMinimumNotificationWinRatePercent(){return minimumNotificationWinRatePercent;}public boolean isEligibleForNotification(){return eligibleForNotification;}public String getNotificationSuppressionReason(){return notificationSuppressionReason;}public boolean isTelegramSent(){return telegramSent;}public NotificationDeliveryStatus getNotificationDeliveryStatus(){return notificationDeliveryStatus;}public String getNotificationError(){return notificationError;}public Instant getCreatedAt(){return createdAt;}public Instant getUpdatedAt(){return updatedAt;}private <T>T invalidLevel(int level){throw new IllegalArgumentException("level must be 1, 2, or 3: "+level);}private List<String>split(String s){return s==null?List.of():Arrays.asList(s.split("\\|\\|",-1));}
}
