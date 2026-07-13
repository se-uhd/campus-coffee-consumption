ALTER TABLE users ADD COLUMN totp_secret varchar(512);
ALTER TABLE users ADD COLUMN totp_enabled boolean DEFAULT false;
