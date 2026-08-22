DO $$
DECLARE
  duplicate_scopes TEXT;
BEGIN
  -- Fresh databases do not have the V18 table yet. Databases that already
  -- applied V19 are protected by the partial unique index and must not be
  -- affected by this forward-only compatibility callback.
  IF to_regclass('public.active_signal_locks') IS NULL
     OR (
       to_regclass('public.flyway_schema_history') IS NOT NULL
       AND EXISTS (
         SELECT 1
         FROM flyway_schema_history
         WHERE version = '19' AND success
       )
     ) THEN
    RETURN;
  END IF;

  EXECUTE 'LOCK TABLE active_signal_locks IN ACCESS EXCLUSIVE MODE';

  SELECT string_agg(
           format(
             'coin_id=%s horizon=%s open_rows=%s',
             coin_id,
             horizon_seconds,
             open_count
           ),
           E'\n'
           ORDER BY coin_id, horizon_seconds
         )
    INTO duplicate_scopes
  FROM (
    SELECT coin_id, horizon_seconds, COUNT(*) AS open_count
    FROM active_signal_locks
    WHERE status = 'OPEN'
    GROUP BY coin_id, horizon_seconds
    HAVING COUNT(*) > 1
  ) duplicates;

  IF duplicate_scopes IS NOT NULL THEN
    RAISE EXCEPTION USING
      MESSAGE = E'Cannot enforce active-signal OPEN uniqueness.\n\nDuplicate scopes:\n'
                || duplicate_scopes,
      HINT = 'Inspect the corresponding MixTradeSimulation TP1/SL1/deadline '
             || 'history, resolve the correct ActiveSignalLock states, then '
             || 'rerun Flyway. No historical lock or simulation was deleted.';
  END IF;

  -- SQL callbacks commit independently from versioned migrations. This
  -- temporary trigger bridges the callback-to-V19 gap and serializes writers
  -- per coin+horizon until V19 installs the authoritative partial index.
  EXECUTE $function$
    CREATE OR REPLACE FUNCTION tornado_guard_active_signal_open_scope()
    RETURNS trigger
    LANGUAGE plpgsql
    AS $body$
    BEGIN
      IF NEW.status = 'OPEN' THEN
        PERFORM pg_advisory_xact_lock(
          hashtextextended(NEW.coin_id || ':' || NEW.horizon_seconds, 0)
        );
        IF EXISTS (
          SELECT 1
          FROM active_signal_locks existing
          WHERE existing.coin_id = NEW.coin_id
            AND existing.horizon_seconds = NEW.horizon_seconds
            AND existing.status = 'OPEN'
            AND (TG_OP = 'INSERT' OR existing.id <> NEW.id)
        ) THEN
          RAISE EXCEPTION USING
            ERRCODE = 'unique_violation',
            CONSTRAINT = 'uk_active_signal_lock_open_coin_horizon',
            MESSAGE = 'An OPEN active-signal lock already exists for coin_id='
                      || NEW.coin_id || ' horizon=' || NEW.horizon_seconds;
        END IF;
      END IF;
      RETURN NEW;
    END
    $body$
  $function$;

  EXECUTE 'DROP TRIGGER IF EXISTS tornado_guard_active_signal_open_scope '
          || 'ON active_signal_locks';
  EXECUTE 'CREATE TRIGGER tornado_guard_active_signal_open_scope '
          || 'BEFORE INSERT OR UPDATE OF coin_id, horizon_seconds, status '
          || 'ON active_signal_locks FOR EACH ROW '
          || 'EXECUTE FUNCTION tornado_guard_active_signal_open_scope()';
END $$;
