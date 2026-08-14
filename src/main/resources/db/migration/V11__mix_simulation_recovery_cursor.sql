ALTER TABLE mix_trade_simulations ADD COLUMN last_checked_at TIMESTAMP WITH TIME ZONE;
UPDATE mix_trade_simulations SET last_checked_at = COALESCE(updated_at, opened_at);
ALTER TABLE mix_trade_simulations ALTER COLUMN last_checked_at SET NOT NULL;

CREATE INDEX idx_mix_sim_open_pair_cursor
    ON mix_trade_simulations(status, coin_id, last_checked_at);
