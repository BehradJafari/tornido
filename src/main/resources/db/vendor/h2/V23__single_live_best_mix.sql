-- Best Method Mix is now the current TP1 live winner, not a per-TP ranking table.
DELETE FROM best_method_mixes
WHERE horizon_seconds NOT IN (3600, 14400, 43200, 86400)
   OR tp_level <> 1
   OR target_percent <> COALESCE((SELECT take_profit1_percent FROM app_settings WHERE id = 1), 0.3000)
   OR samples < COALESCE((SELECT minimum_mix_simulation_trades FROM app_settings WHERE id = 1), 30)
   OR target_hit_rate < COALESCE((SELECT minimum_notification_win_rate_percent FROM app_settings WHERE id = 1), 60.0000);

DELETE FROM best_method_mixes
WHERE id IN (
  SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (
      PARTITION BY coin_id, horizon_seconds, signal_version
      ORDER BY wilson_score DESC, target_hit_rate DESC, samples DESC,
               directional_accuracy DESC, mix_size ASC, strategy_codes ASC,
               strategy_versions ASC, id ASC
    ) AS duplicate_rank
    FROM best_method_mixes
  ) ranked
  WHERE duplicate_rank > 1
);

ALTER TABLE best_method_mixes DROP CONSTRAINT uk_best_mix_slice_version_tp;
ALTER TABLE best_method_mixes ADD CONSTRAINT uk_best_mix_slice_version
  UNIQUE (coin_id, horizon_seconds, signal_version);
DROP INDEX IF EXISTS idx_best_mix_tp_slice;
CREATE INDEX idx_best_mix_live_slice
  ON best_method_mixes(signal_version, coin_id, horizon_seconds);
