package io.tornado.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Immutable, validated percentage ladder used by settings, ranking and simulations. */
public record TpSlLevels(BigDecimal tp1, BigDecimal tp2, BigDecimal tp3,
                         BigDecimal sl1, BigDecimal sl2, BigDecimal sl3) {
    private static final BigDecimal MIN = new BigDecimal("0.01");
    private static final BigDecimal MAX = new BigDecimal("20");

    public TpSlLevels {
        validate("take-profit", tp1, tp2, tp3);
        validate("stop-loss", sl1, sl2, sl3);
    }

    public static TpSlLevels defaults() {
        return new TpSlLevels(new BigDecimal("0.30"), new BigDecimal("0.50"), new BigDecimal("1.00"),
                new BigDecimal("0.30"), new BigDecimal("0.50"), new BigDecimal("1.00"));
    }

    public BigDecimal tp(int level) { return level == 1 ? tp1 : level == 2 ? tp2 : level == 3 ? tp3 : invalid(level); }
    public BigDecimal sl(int level) { return level == 1 ? sl1 : level == 2 ? sl2 : level == 3 ? sl3 : invalid(level); }
    public List<BigDecimal> takeProfits() { return List.of(tp1, tp2, tp3); }
    public List<BigDecimal> stopLosses() { return List.of(sl1, sl2, sl3); }

    public BigDecimal price(BigDecimal entry, Direction direction, boolean takeProfit, int level) {
        BigDecimal fraction = (takeProfit ? tp(level) : sl(level)).movePointLeft(2);
        boolean add = takeProfit ? direction == Direction.UP : direction == Direction.DOWN;
        return entry.multiply(BigDecimal.ONE.add(add ? fraction : fraction.negate())).setScale(12, RoundingMode.HALF_UP);
    }

    private static void validate(String name, BigDecimal first, BigDecimal second, BigDecimal third) {
        if (first == null || second == null || third == null || first.compareTo(MIN) < 0 || third.compareTo(MAX) > 0)
            throw new IllegalArgumentException(name + " levels must be between 0.01 and 20 percent");
        if (first.compareTo(second) >= 0 || second.compareTo(third) >= 0)
            throw new IllegalArgumentException(name + " levels must be strictly increasing");
    }

    private static BigDecimal invalid(int level) { throw new IllegalArgumentException("level must be 1, 2, or 3: " + level); }
}
