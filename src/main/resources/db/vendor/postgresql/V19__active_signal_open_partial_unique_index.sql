CREATE UNIQUE INDEX uk_active_signal_lock_open_coin_horizon
  ON active_signal_locks(coin_id, horizon_seconds)
  WHERE status = 'OPEN';
