ALTER TABLE mix_trade_simulations
  ADD COLUMN trade_outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE mix_trade_simulations
  ADD COLUMN success_at TIMESTAMP WITH TIME ZONE;

UPDATE mix_trade_simulations
SET trade_outcome = 'SUCCESS',
    success_at = tp1_hit_at
WHERE tp1_hit_at IS NOT NULL;

CREATE INDEX idx_mix_simulation_trade_outcome_opened
  ON mix_trade_simulations(trade_outcome, opened_at DESC);
