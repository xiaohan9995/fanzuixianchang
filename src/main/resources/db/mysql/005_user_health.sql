-- Apply once to the selected MySQL database, after 004_user_profile.sql.
-- One row per account: the confirmed profile and the append-only goal history.
-- Daily markers (exercise, skipped meals, day completion) stay device-local and are never stored here.
CREATE TABLE fanzui_user_health (
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 health_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 profile_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
 goals_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id),
 CONSTRAINT fk_health_user FOREIGN KEY(user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
