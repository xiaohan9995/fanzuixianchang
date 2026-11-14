-- Apply once to the selected WeChat CloudRun MySQL database before OPS_STORAGE=mysql.
-- Inspect the existing schema first. Do not use this script to overwrite an existing table.
-- Runtime account needs SELECT, INSERT, UPDATE only; no DDL privileges are required.
CREATE TABLE fanzui_ops_state (
  namespace VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (namespace),
  CONSTRAINT chk_fanzui_ops_namespace CHECK (namespace IN ('content','workflows')),
  CONSTRAINT chk_fanzui_ops_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
