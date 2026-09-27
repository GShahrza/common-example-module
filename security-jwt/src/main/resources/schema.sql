-- One row per refresh token. A "family" is one login (one device); rotation adds rows to it.
CREATE TABLE IF NOT EXISTS refresh_token (
    token_hash      CHAR(64)                 PRIMARY KEY,   -- SHA-256 of the token, never the token itself
    username        VARCHAR(100)             NOT NULL,
    family_id       CHAR(36)                 NOT NULL,
    device          VARCHAR(200),                            -- User-Agent at login, shown in "active sessions"
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    family_expires  TIMESTAMP WITH TIME ZONE NOT NULL,       -- absolute end of the login
    used_at         TIMESTAMP WITH TIME ZONE                 -- set on rotation; a second use = theft
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_user ON refresh_token (username);
