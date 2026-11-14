-- Apply once to the selected MySQL database, after 002_user_accounts_and_sync.sql.
-- Nickname and avatar are optional; avatar bytes live in object storage, not in MySQL.
ALTER TABLE fanzui_users
  ADD COLUMN nickname VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  ADD COLUMN avatar_object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN avatar_mime_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL;
