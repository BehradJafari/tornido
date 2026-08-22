DROP TRIGGER IF EXISTS tornado_guard_active_signal_open_scope
  ON active_signal_locks;

DROP FUNCTION IF EXISTS tornado_guard_active_signal_open_scope();
