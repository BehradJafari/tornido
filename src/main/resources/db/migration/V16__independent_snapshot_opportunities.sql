-- TP/SL milestones are an audit trail for each opportunity, not a lock that
-- prevents a later qualifying snapshot from creating its own trade.
UPDATE mix_trade_simulations
SET active_key = NULL
WHERE active_key IS NOT NULL;
