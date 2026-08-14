ALTER TABLE app_settings ADD COLUMN telegram_notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_settings ADD COLUMN telegram_bot_token VARCHAR(255);
ALTER TABLE app_settings ADD COLUMN telegram_chat_id VARCHAR(120);
