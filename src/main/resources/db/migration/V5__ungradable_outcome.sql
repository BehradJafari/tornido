-- No PostgreSQL type change is required: outcome is VARCHAR(12), which fits UNGRADABLE.
CREATE INDEX idx_prediction_grading_attempts ON predictions(outcome, signal_version, grading_attempts, predicted_at);
