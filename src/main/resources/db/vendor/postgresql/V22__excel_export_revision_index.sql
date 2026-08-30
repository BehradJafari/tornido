-- Makes Excel cache freshness checks an indexed MAX lookup instead of a table scan.
CREATE INDEX IF NOT EXISTS idx_predictions_excel_revision
  ON predictions(signal_version, graded_at DESC)
  WHERE outcome IN ('CORRECT', 'INCORRECT');
