-- H2 does not support PostgreSQL partial indexes; include outcome in the key.
CREATE INDEX IF NOT EXISTS idx_predictions_excel_revision
  ON predictions(signal_version, outcome, graded_at DESC);
