-- Scheduler: fetch only predictions whose grading target is due.
CREATE INDEX idx_predictions_due_target
  ON predictions(outcome, signal_version, target_at);

-- Report projections and method-mix grouping.
CREATE INDEX idx_predictions_report_horizon
  ON predictions(signal_version, outcome, horizon_seconds, coin_id, analysis_run_id, method_name);

-- Run summaries and run detail screens.
CREATE INDEX idx_predictions_run_outcome
  ON predictions(analysis_run_id, outcome);

-- Method report filtering and ordering.
CREATE INDEX idx_predictions_method_horizon_time
  ON predictions(method_name, signal_version, horizon_seconds, predicted_at DESC);
