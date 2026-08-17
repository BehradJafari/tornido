ALTER TABLE mix_trade_simulations
  ADD COLUMN failure_at TIMESTAMP WITH TIME ZONE;

-- Recalculate existing outcomes using first touch between TP1 and SL1.
UPDATE mix_trade_simulations
SET trade_outcome = CASE
      WHEN tp1_hit_at IS NULL AND sl1_hit_at IS NULL THEN 'PENDING'
      WHEN tp1_hit_at IS NOT NULL AND sl1_hit_at IS NULL THEN 'SUCCESS'
      WHEN tp1_hit_at IS NULL THEN 'FAILED'
      WHEN tp1_hit_at < sl1_hit_at THEN 'SUCCESS'
      WHEN sl1_hit_at < tp1_hit_at THEN 'FAILED'
      WHEN tp1_hit_sequence IS NOT NULL AND sl1_hit_sequence IS NOT NULL
           AND tp1_hit_sequence < sl1_hit_sequence THEN 'SUCCESS'
      WHEN tp1_hit_sequence IS NOT NULL AND sl1_hit_sequence IS NOT NULL
           AND sl1_hit_sequence < tp1_hit_sequence THEN 'FAILED'
      ELSE 'PENDING'
    END,
    success_at = CASE
      WHEN sl1_hit_at IS NULL OR (tp1_hit_at IS NOT NULL AND tp1_hit_at < sl1_hit_at)
        OR (tp1_hit_at = sl1_hit_at AND tp1_hit_sequence IS NOT NULL
            AND sl1_hit_sequence IS NOT NULL AND tp1_hit_sequence < sl1_hit_sequence)
      THEN tp1_hit_at ELSE NULL END,
    failure_at = CASE
      WHEN tp1_hit_at IS NULL OR (sl1_hit_at IS NOT NULL AND sl1_hit_at < tp1_hit_at)
        OR (tp1_hit_at = sl1_hit_at AND tp1_hit_sequence IS NOT NULL
            AND sl1_hit_sequence IS NOT NULL AND sl1_hit_sequence < tp1_hit_sequence)
      THEN sl1_hit_at ELSE NULL END;
