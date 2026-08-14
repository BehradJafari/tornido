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
    public record Observation(boolean changed, List<Milestone> milestones, boolean terminal) {
        public static Observation none() { return new Observation(false, List.of(), false); }
    }

    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="coin_id") private Coin coin;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="best_mix_id") private BestMethodMix bestMix;
    private long horizonSeconds; private int mixSize; private int mixRank;
    @Column(length=2000) private String methodNames; @Column(length=1000) private String strategyCodes;
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
    private Instant createdAt; private Instant updatedAt;

    protected MixTradeSimulation() {}
    public MixTradeSimulation(Coin coin,BestMethodMix mix,Direction direction,int agreement,BigDecimal entry,TpSlLevels levels,Instant opened) {
        this.coin=coin;bestMix=mix;horizonSeconds=mix.getHorizonSeconds();mixSize=mix.getMixSize();mixRank=mix.getRank();
        methodNames=String.join("||",mix.getMethodNames());strategyCodes=String.join("||",mix.getStrategyCodes());this.direction=direction;
        agreementCount=agreement;totalMethods=mixSize;historicalSamples=mix.getSamples();historicalTargetHitRate=mix.getTargetHitRate();historicalDirectionalAccuracy=mix.getDirectionalAccuracy();historicalWilsonScore=mix.getWilsonScore();
        openedAt=opened;entryPrice=entry;tp1Percent=levels.tp1();tp2Percent=levels.tp2();tp3Percent=levels.tp3();sl1Percent=levels.sl1();sl2Percent=levels.sl2();sl3Percent=levels.sl3();
        tp1Price=levels.price(entry,direction,true,1);tp2Price=levels.price(entry,direction,true,2);tp3Price=levels.price(entry,direction,true,3);
        sl1Price=levels.price(entry,direction,false,1);sl2Price=levels.price(entry,direction,false,2);sl3Price=levels.price(entry,direction,false,3);
        targetPrice=tp3Price;stopLossPrice=sl3Price;status=Status.OPEN;lastCheckedPrice=entry;lastCheckedAt=opened;
        activeKey=activeKey(coin,mix,direction);createdAt=opened;updatedAt=opened;
    }
    /** Compatibility constructor for callers compiled against the old API. */
    public MixTradeSimulation(Coin coin,BestMethodMix mix,Direction direction,int agreement,BigDecimal entry,BigDecimal ignoredLegacyStop,Instant opened) { this(coin,mix,direction,agreement,entry,TpSlLevels.defaults(),opened); }

    public static String activeKey(Coin coin,BestMethodMix mix,Direction direction) { StringBuilder key=new StringBuilder().append(coin.getId()).append(':').append(mix.getHorizonSeconds()).append(':');List<String>codes=mix.getStrategyCodes();List<Integer>versions=mix.getStrategyVersions();for(int i=0;i<codes.size();i++){if(i>0)key.append(',');key.append(codes.get(i)).append('@').append(versions.get(i));}return key.append(':').append(direction).toString(); }
    public void telegramMessage(Long id){telegramMessageId=id;updatedAt=Instant.now();}

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

    public Long getId(){return id;}public Coin getCoin(){return coin;}public BestMethodMix getBestMix(){return bestMix;}public long getHorizonSeconds(){return horizonSeconds;}public int getMixSize(){return mixSize;}public int getMixRank(){return mixRank;}public List<String>getMethodNames(){return split(methodNames);}public List<String>getStrategyCodes(){return split(strategyCodes);}public Direction getDirection(){return direction;}public int getAgreementCount(){return agreementCount;}public int getTotalMethods(){return totalMethods;}public long getHistoricalSamples(){return historicalSamples;}public double getHistoricalTargetHitRate(){return historicalTargetHitRate;}public double getHistoricalDirectionalAccuracy(){return historicalDirectionalAccuracy;}public double getHistoricalWilsonScore(){return historicalWilsonScore;}public Instant getOpenedAt(){return openedAt;}public BigDecimal getEntryPrice(){return entryPrice;}public BigDecimal getTargetPrice(){return targetPrice;}public BigDecimal getStopLossPrice(){return stopLossPrice;}public BigDecimal getTp1Percent(){return tp1Percent;}public BigDecimal getTp2Percent(){return tp2Percent;}public BigDecimal getTp3Percent(){return tp3Percent;}public BigDecimal getSl1Percent(){return sl1Percent;}public BigDecimal getSl2Percent(){return sl2Percent;}public BigDecimal getSl3Percent(){return sl3Percent;}public BigDecimal getTp1Price(){return tp1Price;}public BigDecimal getTp2Price(){return tp2Price;}public BigDecimal getTp3Price(){return tp3Price;}public BigDecimal getSl1Price(){return sl1Price;}public BigDecimal getSl2Price(){return sl2Price;}public BigDecimal getSl3Price(){return sl3Price;}public Instant getTp1HitAt(){return tp1HitAt;}public Instant getTp2HitAt(){return tp2HitAt;}public Instant getTp3HitAt(){return tp3HitAt;}public Instant getSl1HitAt(){return sl1HitAt;}public Instant getSl2HitAt(){return sl2HitAt;}public Instant getSl3HitAt(){return sl3HitAt;}public Status getStatus(){return status;}public Instant getClosedAt(){return closedAt;}public BigDecimal getClosePrice(){return closePrice;}public BigDecimal getLastCheckedPrice(){return lastCheckedPrice;}public Instant getLastCheckedAt(){return lastCheckedAt;}public Double getResultPercent(){return resultPercent;}public Long getTelegramMessageId(){return telegramMessageId;}public Instant getCreatedAt(){return createdAt;}public Instant getUpdatedAt(){return updatedAt;}private List<String>split(String s){return s==null?List.of():Arrays.asList(s.split("\\|\\|",-1));}
}
