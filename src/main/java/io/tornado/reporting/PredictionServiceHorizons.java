package io.tornado.reporting;

import java.util.List;
import java.util.Set;

/** Central horizon policy shared by reporting and live signal admission. */
public final class PredictionServiceHorizons {
    public static final List<Long> ALL = List.of(60L, 900L, 1800L, 3600L, 14400L, 43200L, 86400L);
    public static final List<Long> LIVE_SIGNAL_HORIZONS_SORTED = List.of(3600L, 14400L, 43200L, 86400L);
    public static final Set<Long> LIVE_SIGNAL_HORIZONS = Set.of(3600L, 14400L, 43200L, 86400L);
    private PredictionServiceHorizons() {}
    public static boolean supportsLiveSignal(long horizonSeconds) { return LIVE_SIGNAL_HORIZONS.contains(horizonSeconds); }
}
