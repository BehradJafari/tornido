package io.tornado.reporting;

import io.tornado.api.ReportService;
import io.tornado.persistence.*;
import io.tornado.persistence.PredictionRepository.ReportRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.LongConsumer;

@Service
public class BestMixService {
    static final Comparator<Candidate> STRONGEST_FIRST = Comparator
            .comparingDouble(Candidate::wilson).reversed()
            .thenComparing(Comparator.comparingLong(Candidate::samples).reversed())
            .thenComparing(Comparator.comparingDouble(Candidate::targetHitRate).reversed())
            .thenComparing(Comparator.comparingDouble(Candidate::directionalAccuracy).reversed())
            .thenComparingInt(Candidate::size)
            .thenComparing(Candidate::strategyIdentity);

    private final PredictionRepository predictions;
    private final BestMethodMixRepository mixes;
    private final CoinRepository coins;
    private final AppSettingsRepository settings;

    public BestMixService(PredictionRepository predictions, BestMethodMixRepository mixes,
                          CoinRepository coins, AppSettingsRepository settings) {
        this.predictions = predictions;
        this.mixes = mixes;
        this.coins = coins;
        this.settings = settings;
    }

    /** Replaces a reporting slice with exactly one strongest row for each TP level. */
    @Transactional
    public synchronized void refresh(long coinId, long horizon) {
        ReportService.requireSupportedHorizon(horizon);
        Coin coin = coins.findById(coinId).orElseThrow();
        AppSettings configuration = settings.findById(1).orElseThrow();
        TpSlLevels levels = configuration.getTpSlLevels();
        Map<Integer, List<Candidate>> ranked = calculateAll(
                predictions.findGradedReportRows(coinId, horizon),
                configuration.getMinimumMixSimulationTrades(), levels);

        mixes.deleteSlice(coinId, horizon, Prediction.CURRENT_SIGNAL_VERSION);
        mixes.flush();

        List<BestMethodMix> winners = new ArrayList<>(3);
        for (int tpLevel = 1; tpLevel <= 3; tpLevel++) {
            List<Candidate> candidates = ranked.getOrDefault(tpLevel, List.of());
            if (candidates.isEmpty()) continue;
            Candidate winner = candidates.getFirst();
            winners.add(new BestMethodMix(coin, horizon, winner.size(), 1,
                    winner.codes(), winner.versions(), winner.names(), winner.samples(),
                    winner.hits(), winner.directional(), winner.wilson(), tpLevel, levels.tp(tpLevel)));
        }
        mixes.saveAll(winners);
    }

    @Transactional
    public synchronized void refresh(Collection<Slice> slices) {
        for (Slice slice : slices) refresh(slice.coinId(), slice.horizon());
    }

    @Transactional
    public synchronized void rebuildAll() {
        for (Coin coin : coins.findAllByActiveTrueOrderBySymbol()) {
            for (long horizon : PredictionServiceHorizons.ALL) refresh(coin.getId(), horizon);
        }
    }

    List<Candidate> calculate(List<ReportRow> rows, int minimum) {
        return calculateAll(rows, minimum, TpSlLevels.defaults()).get(1);
    }

    Map<Integer, List<Candidate>> calculateAll(List<ReportRow> rows, int minimum, TpSlLevels levels) {
        List<Identity> identities = rows.stream()
                .map(row -> new Identity(row.getStrategyCode(), row.getStrategyVersion(), row.getMethodName()))
                .distinct().sorted(Comparator.comparing(Identity::code).thenComparingInt(Identity::version)).toList();
        if (identities.size() > 63) throw new IllegalStateException("at most 63 strategy versions are supported");

        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < identities.size(); index++) indexes.put(identities.get(index).key(), index);
        Map<GroupKey, Group> groups = new HashMap<>();
        for (ReportRow row : rows) {
            if (row.getRunId() == null) continue;
            groups.computeIfAbsent(new GroupKey(row.getRunId(), row.getCoinId()), ignored -> new Group(row))
                    .put(indexes.get(new Identity(row.getStrategyCode(), row.getStrategyVersion(), row.getMethodName()).key()), row.getPredictedDirection());
        }

        Map<Integer, Map<Long, Score>> scores = new HashMap<>();
        for (int size = 2; size <= 8; size++) scores.put(size, new HashMap<>());
        for (Group group : groups.values()) scoreGroup(group, scores, levels);

        Map<Integer, List<Candidate>> result = new LinkedHashMap<>();
        for (int tpLevel = 1; tpLevel <= 3; tpLevel++) {
            int selectedTp = tpLevel;
            List<Candidate> candidates = new ArrayList<>();
            scores.forEach((size, byMask) -> byMask.forEach((mask, score) -> {
                if (score.samples < minimum) return;
                List<Identity> selected = decode(mask, identities);
                candidates.add(new Candidate(size,
                        selected.stream().map(Identity::code).toList(),
                        selected.stream().map(Identity::version).toList(),
                        selected.stream().map(Identity::name).toList(), score.samples,
                        score.hits[selectedTp - 1], score.directional,
                        wilson(score.hits[selectedTp - 1], score.samples)));
            }));
            candidates.sort(STRONGEST_FIRST);
            result.put(tpLevel, List.copyOf(candidates));
        }
        return result;
    }

    private void scoreGroup(Group group, Map<Integer, Map<Long, Score>> scores, TpSlLevels levels) {
        for (int size = 2; size <= 8; size++) {
            if (Long.bitCount(group.methods) < size) continue;
            int selectedSize = size;
            combinations(group.methods, size, 0, mask -> {
                int ups = Long.bitCount(mask & group.ups);
                int downs = selectedSize - ups;
                if (ups == downs) return;
                boolean predictedUp = ups > downs;
                Score score = scores.get(selectedSize).computeIfAbsent(mask, ignored -> new Score());
                score.samples++;
                for (int tp = 1; tp <= 3; tp++) if (group.target(predictedUp, levels.tp(tp))) score.hits[tp - 1]++;
                if (group.direction(predictedUp)) score.directional++;
            });
        }
    }

    private void combinations(long remaining, int needed, long selected, LongConsumer consumer) {
        if (needed == 0) { consumer.accept(selected); return; }
        while (Long.bitCount(remaining) >= needed) {
            long bit = Long.lowestOneBit(remaining);
            remaining ^= bit;
            combinations(remaining, needed - 1, selected | bit, consumer);
        }
    }

    private List<Identity> decode(long mask, List<Identity> identities) {
        List<Identity> result = new ArrayList<>();
        while (mask != 0) {
            long bit = Long.lowestOneBit(mask);
            result.add(identities.get(Long.numberOfTrailingZeros(bit)));
            mask ^= bit;
        }
        return result;
    }

    private double wilson(long hits, long samples) {
        double z = 1.96, p = (double) hits / samples, z2 = z * z;
        return (p + z2 / (2 * samples) - z * Math.sqrt((p * (1 - p) + z2 / (4 * samples)) / samples))
                / (1 + z2 / samples);
    }

    public record Slice(long coinId, long horizon) {}
    record Identity(String code, int version, String name) { String key() { return code + "\u0000" + version; } }
    record GroupKey(long run, long coin) {}
    static class Score { long samples, directional; long[] hits = new long[3]; }

    static class Group {
        long methods, ups;
        final BigDecimal upwardReturn;
        Group(ReportRow row) { upwardReturn = row.getPriceAtGrading().subtract(row.getPriceAtPrediction()).divide(row.getPriceAtPrediction(), 12, RoundingMode.HALF_UP); }
        void put(int index, Direction direction) { long bit = 1L << index; methods |= bit; if (direction == Direction.UP) ups |= bit; }
        boolean target(boolean up, BigDecimal percent) { return (up ? upwardReturn : upwardReturn.negate()).compareTo(percent.movePointLeft(2)) >= 0; }
        boolean direction(boolean up) { return up ? upwardReturn.signum() > 0 : upwardReturn.signum() < 0; }
    }

    record Candidate(int size, List<String> codes, List<Integer> versions, List<String> names,
                     long samples, long hits, long directional, double wilson) {
        double targetHitRate() { return samples == 0 ? 0 : hits * 100.0 / samples; }
        double directionalAccuracy() { return samples == 0 ? 0 : directional * 100.0 / samples; }
        String strategyIdentity() {
            List<String> identities = new ArrayList<>();
            for (int i = 0; i < codes.size(); i++) identities.add(codes.get(i) + "@" + versions.get(i));
            identities.sort(String::compareTo);
            return String.join(",", identities);
        }
    }
}
