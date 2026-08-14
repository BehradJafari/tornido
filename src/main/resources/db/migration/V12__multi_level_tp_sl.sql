ALTER TABLE app_settings ADD COLUMN take_profit1_percent NUMERIC(8,4) NOT NULL DEFAULT 0.3000;
ALTER TABLE app_settings ADD COLUMN take_profit2_percent NUMERIC(8,4) NOT NULL DEFAULT 0.5000;
ALTER TABLE app_settings ADD COLUMN take_profit3_percent NUMERIC(8,4) NOT NULL DEFAULT 1.0000;
ALTER TABLE app_settings ADD COLUMN stop_loss1_percent NUMERIC(8,4) NOT NULL DEFAULT 0.3000;
ALTER TABLE app_settings ADD COLUMN stop_loss2_percent NUMERIC(8,4) NOT NULL DEFAULT 0.5000;
ALTER TABLE app_settings ADD COLUMN stop_loss3_percent NUMERIC(8,4) NOT NULL DEFAULT 1.0000;

ALTER TABLE best_method_mixes ADD COLUMN tp_level INTEGER NOT NULL DEFAULT 1;
ALTER TABLE best_method_mixes ADD COLUMN target_percent NUMERIC(8,4) NOT NULL DEFAULT 0.3000;
ALTER TABLE best_method_mixes DROP CONSTRAINT uk_best_mix_slice_rank_version;
ALTER TABLE best_method_mixes ADD CONSTRAINT uk_best_mix_slice_rank_version_tp UNIQUE(coin_id,horizon_seconds,mix_size,rank_number,signal_version,tp_level);
CREATE INDEX idx_best_mix_tp_slice ON best_method_mixes(coin_id,horizon_seconds,signal_version,tp_level,mix_size,rank_number);

ALTER TABLE mix_trade_simulations ADD COLUMN tp1_percent NUMERIC(8,4);
ALTER TABLE mix_trade_simulations ADD COLUMN tp2_percent NUMERIC(8,4);
ALTER TABLE mix_trade_simulations ADD COLUMN tp3_percent NUMERIC(8,4);
ALTER TABLE mix_trade_simulations ADD COLUMN sl1_percent NUMERIC(8,4);
ALTER TABLE mix_trade_simulations ADD COLUMN sl2_percent NUMERIC(8,4);
ALTER TABLE mix_trade_simulations ADD COLUMN sl3_percent NUMERIC(8,4);
ALTER TABLE mix_trade_simulations ADD COLUMN tp1_price NUMERIC(30,12);
ALTER TABLE mix_trade_simulations ADD COLUMN tp2_price NUMERIC(30,12);
ALTER TABLE mix_trade_simulations ADD COLUMN tp3_price NUMERIC(30,12);
ALTER TABLE mix_trade_simulations ADD COLUMN sl1_price NUMERIC(30,12);
ALTER TABLE mix_trade_simulations ADD COLUMN sl2_price NUMERIC(30,12);
ALTER TABLE mix_trade_simulations ADD COLUMN sl3_price NUMERIC(30,12);
ALTER TABLE mix_trade_simulations ADD COLUMN tp1_hit_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE mix_trade_simulations ADD COLUMN tp2_hit_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE mix_trade_simulations ADD COLUMN tp3_hit_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE mix_trade_simulations ADD COLUMN sl1_hit_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE mix_trade_simulations ADD COLUMN sl2_hit_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE mix_trade_simulations ADD COLUMN sl3_hit_at TIMESTAMP WITH TIME ZONE;

-- Preserve what old rows really recorded. Deeper levels remain NULL and are displayed as legacy history.
UPDATE mix_trade_simulations SET tp1_percent=0.3000,tp1_price=target_price,
 tp1_hit_at=CASE WHEN status='TARGET_HIT' THEN closed_at ELSE NULL END,
 sl1_percent=CASE WHEN entry_price<>0 THEN ABS((stop_loss_price-entry_price)/entry_price*100) ELSE NULL END,
 sl1_price=stop_loss_price,sl1_hit_at=CASE WHEN status='STOP_LOSS_HIT' THEN closed_at ELSE NULL END;
