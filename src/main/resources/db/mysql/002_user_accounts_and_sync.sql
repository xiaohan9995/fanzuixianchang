-- Apply once to the selected WeChat CloudRun MySQL database after inspecting existing objects.
-- The application account needs SELECT/INSERT/UPDATE/DELETE on these tables; it does not run DDL.
CREATE TABLE fanzui_users (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'active',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id),
  CONSTRAINT chk_fanzui_user_status CHECK (status IN ('active','deleting'))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE fanzui_wechat_identities (
  app_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  identity_hash CHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (app_id,identity_hash),
  KEY idx_fanzui_identity_user (user_id),
  CONSTRAINT fk_fanzui_identity_user FOREIGN KEY (user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE fanzui_user_consents (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  policy_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  granted BOOLEAN NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id,purpose),
  CONSTRAINT fk_fanzui_consent_user FOREIGN KEY (user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE fanzui_user_consent_events (
  consent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  policy_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  granted BOOLEAN NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (consent_id),
  KEY idx_fanzui_consent_events_user_time (user_id,created_at),
  CONSTRAINT fk_fanzui_consent_event_user FOREIGN KEY (user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE fanzui_user_meals (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_version BIGINT UNSIGNED NOT NULL,
  payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id,record_id),
  KEY idx_fanzui_meals_user_updated (user_id,updated_at),
  CONSTRAINT fk_fanzui_meal_user FOREIGN KEY (user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Commercial data foundation only: payment flows and point-award rules stay disabled until configured.
CREATE TABLE fanzui_membership_plans (
  plan_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  entitlement_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (plan_code)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE fanzui_membership_orders (
  order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  plan_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  merchant_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_trade_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_fanzui_merchant_order (merchant_order_no),
  UNIQUE KEY uk_fanzui_provider_trade (provider_trade_no),
  KEY idx_fanzui_orders_user (user_id,created_at),
  CONSTRAINT fk_fanzui_order_user FOREIGN KEY (user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE fanzui_user_entitlements (
  entitlement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  plan_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  starts_at TIMESTAMP(6) NOT NULL,
  expires_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (entitlement_id),
  KEY idx_fanzui_entitlement_user (user_id,status,starts_at,expires_at),
  CONSTRAINT fk_fanzui_entitlement_user FOREIGN KEY (user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_fanzui_entitlement_order FOREIGN KEY (source_order_id) REFERENCES fanzui_membership_orders(order_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE fanzui_points_ledger (
  ledger_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  points_delta INT NOT NULL,
  event_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idempotency_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reference_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (ledger_id),
  UNIQUE KEY uk_fanzui_points_idempotency (user_id,idempotency_key),
  KEY idx_fanzui_points_user_time (user_id,created_at),
  CONSTRAINT fk_fanzui_points_user FOREIGN KEY (user_id) REFERENCES fanzui_users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
