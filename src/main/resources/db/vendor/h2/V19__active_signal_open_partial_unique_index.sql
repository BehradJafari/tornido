ALTER TABLE active_signal_locks ADD COLUMN open_scope_key VARCHAR(100)
  GENERATED ALWAYS AS (
    CASE WHEN status = 'OPEN'
      THEN CAST(coin_id AS VARCHAR) || ':' || CAST(horizon_seconds AS VARCHAR)
      ELSE NULL
    END
  );

CREATE UNIQUE INDEX uk_active_signal_lock_open_coin_horizon
  ON active_signal_locks(open_scope_key);
