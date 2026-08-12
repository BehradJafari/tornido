ALTER TABLE predictions ADD COLUMN signal_version INTEGER NOT NULL DEFAULT 1;
CREATE INDEX idx_predictions_signal_version ON predictions(signal_version, outcome, predicted_at);
