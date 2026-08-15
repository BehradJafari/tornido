ALTER TABLE mix_trade_simulations ADD COLUMN tp1_hit_sequence BIGINT;
ALTER TABLE mix_trade_simulations ADD COLUMN tp2_hit_sequence BIGINT;
ALTER TABLE mix_trade_simulations ADD COLUMN tp3_hit_sequence BIGINT;
ALTER TABLE mix_trade_simulations ADD COLUMN sl1_hit_sequence BIGINT;
ALTER TABLE mix_trade_simulations ADD COLUMN sl2_hit_sequence BIGINT;
ALTER TABLE mix_trade_simulations ADD COLUMN sl3_hit_sequence BIGINT;
ALTER TABLE mix_trade_simulations ADD COLUMN last_checked_sequence BIGINT;

UPDATE mix_trade_simulations
SET active_key = NULL
WHERE eligible_for_notification = FALSE;
