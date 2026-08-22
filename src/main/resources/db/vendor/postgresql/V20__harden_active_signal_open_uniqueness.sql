LOCK TABLE active_signal_locks IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
  duplicate_scopes TEXT;
BEGIN
  SELECT string_agg(
           coin_id || ':' || horizon_seconds || ' (' || open_count || ' OPEN rows)',
           ', '
         )
    INTO duplicate_scopes
  FROM (
    SELECT coin_id, horizon_seconds, COUNT(*) AS open_count
    FROM active_signal_locks
    WHERE status = 'OPEN'
    GROUP BY coin_id, horizon_seconds
    HAVING COUNT(*) > 1
    ORDER BY coin_id, horizon_seconds
  ) duplicates;

  IF duplicate_scopes IS NOT NULL THEN
    RAISE EXCEPTION USING
      MESSAGE = 'Cannot enforce active-signal OPEN uniqueness; duplicate scopes: '
                || duplicate_scopes,
      HINT = 'Inspect duplicate locks and resolve their true TP1/SL1/timeout outcome; '
             || 'do not delete their historical simulations. Then rerun Flyway.';
  END IF;
END $$;

DROP INDEX IF EXISTS uk_active_signal_lock_open_coin_horizon;

CREATE UNIQUE INDEX uk_active_signal_lock_open_coin_horizon
  ON active_signal_locks (coin_id, horizon_seconds)
  WHERE status = 'OPEN';
