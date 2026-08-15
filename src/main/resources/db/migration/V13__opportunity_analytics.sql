ALTER TABLE app_settings
  ADD COLUMN minimum_notification_win_rate_percent NUMERIC(8,4) NOT NULL DEFAULT 60.0000;

ALTER TABLE mix_trade_simulations ADD COLUMN signal_version INTEGER NOT NULL DEFAULT 3;
ALTER TABLE mix_trade_simulations ADD COLUMN strategy_versions VARCHAR(1000) NOT NULL DEFAULT '';
ALTER TABLE mix_trade_simulations ADD COLUMN ranking_tp_level INTEGER NOT NULL DEFAULT 1;
ALTER TABLE mix_trade_simulations ADD COLUMN ranking_target_percent NUMERIC(8,4) NOT NULL DEFAULT 0.3000;
ALTER TABLE mix_trade_simulations ADD COLUMN minimum_notification_win_rate_percent NUMERIC(8,4) NOT NULL DEFAULT 60.0000;
ALTER TABLE mix_trade_simulations ADD COLUMN eligible_for_notification BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mix_trade_simulations ADD COLUMN notification_suppression_reason VARCHAR(64);
ALTER TABLE mix_trade_simulations ADD COLUMN telegram_sent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mix_trade_simulations ADD COLUMN notification_delivery_status VARCHAR(16) NOT NULL DEFAULT 'NOT_ATTEMPTED';
ALTER TABLE mix_trade_simulations ADD COLUMN notification_error VARCHAR(500);

UPDATE mix_trade_simulations
SET telegram_sent = CASE WHEN telegram_message_id IS NOT NULL THEN TRUE ELSE FALSE END,
    notification_delivery_status = CASE WHEN telegram_message_id IS NOT NULL THEN 'SENT' ELSE 'LEGACY' END,
    notification_suppression_reason = CASE WHEN telegram_message_id IS NULL THEN 'LEGACY_RECORD' ELSE NULL END;

CREATE INDEX idx_opportunity_coin_detected ON mix_trade_simulations(coin_id, opened_at DESC);
CREATE INDEX idx_opportunity_horizon_detected ON mix_trade_simulations(horizon_seconds, opened_at DESC);
CREATE INDEX idx_opportunity_status_detected ON mix_trade_simulations(status, opened_at DESC);
CREATE INDEX idx_opportunity_notification_detected ON mix_trade_simulations(eligible_for_notification, opened_at DESC);
CREATE INDEX idx_opportunity_signal_tp_detected ON mix_trade_simulations(signal_version, ranking_tp_level, opened_at DESC);
