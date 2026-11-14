-- Apply after 002_user_accounts_and_sync.sql. No FK: deletion jobs must survive account removal.
CREATE TABLE fanzui_meal_photos (
 file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 record_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 object_key VARCHAR(240) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 mime_type VARCHAR(32) NOT NULL,
 size_bytes INT NOT NULL,
 state VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(file_id),
 KEY idx_meal_photos_owner(user_id,record_id,state),
 KEY idx_meal_photos_cleanup(state,created_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
