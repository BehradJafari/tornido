package io.tornado.strategies;
import java.util.*;
public final class StrategyProfilePolicy{
 private StrategyProfilePolicy(){}
 public static final List<Long>HORIZONS=List.of(60L,900L,1800L,3600L,14400L,43200L,86400L);
 public static String fallback(long horizon){return switch((int)horizon){case 60->"1m";case 900->"5m";case 1800->"15m";case 3600->"15m";case 14400->"1h";case 43200->"4h";case 86400->"4h";default->throw new IllegalArgumentException("Unsupported horizon "+horizon);};}
 public static List<String>candidateTimeframes(long horizon){return switch((int)horizon){case 60->List.of("1m","3m");case 900->List.of("1m","3m","5m","15m");case 1800->List.of("3m","5m","15m","30m");case 3600->List.of("5m","15m","30m","1h");case 14400->List.of("15m","30m","1h","2h","4h");case 43200->List.of("30m","1h","2h","4h","6h");case 86400->List.of("1h","2h","4h","6h","8h","12h","1d");default->throw new IllegalArgumentException("Unsupported horizon "+horizon);};}
 public static java.time.Duration duration(String timeframe){long n=Long.parseLong(timeframe.substring(0,timeframe.length()-1));return switch(timeframe.charAt(timeframe.length()-1)){case'm'->java.time.Duration.ofMinutes(n);case'h'->java.time.Duration.ofHours(n);case'd'->java.time.Duration.ofDays(n);default->throw new IllegalArgumentException("Unsupported timeframe "+timeframe);};}
}
