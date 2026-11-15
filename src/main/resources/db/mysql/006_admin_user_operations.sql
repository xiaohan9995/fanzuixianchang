-- Apply once after 005_user_health.sql.
CREATE TABLE IF NOT EXISTS fanzui_ops_admin_events (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(300) NULL,
  detail_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id),
  KEY idx_fanzui_ops_admin_events_time (created_at),
  KEY idx_fanzui_ops_admin_events_target (target_type,target_id,created_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fanzui_ops_settings (
  setting_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  value_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
