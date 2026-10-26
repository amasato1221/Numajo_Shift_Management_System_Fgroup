-- シフト管理システム テーブル作成（40_DB設計書 第1.0版）
CREATE TABLE users (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  login_id              VARCHAR(20)  NOT NULL UNIQUE,
  password_hash         VARCHAR(100) NOT NULL,
  name                  VARCHAR(50)  NOT NULL,
  name_kana             VARCHAR(50),
  role                  ENUM('ADMIN','MANAGER','STAFF') NOT NULL,
  is_trainee            BOOLEAN NOT NULL DEFAULT FALSE,
  is_active             BOOLEAN NOT NULL DEFAULT TRUE,
  must_change_password  BOOLEAN NOT NULL DEFAULT TRUE,
  failed_login_count    INT NOT NULL DEFAULT 0,
  locked_until          DATETIME,
  created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE skills (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(50) NOT NULL UNIQUE,
  description VARCHAR(200),
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_skills (
  user_id  BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, skill_id),
  FOREIGN KEY (user_id)  REFERENCES users(id)  ON DELETE CASCADE,
  FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
);

-- 固定シフト枠（日勤・夕勤）
CREATE TABLE slots (
  id         TINYINT PRIMARY KEY,
  code       VARCHAR(10) NOT NULL UNIQUE,
  name       VARCHAR(10) NOT NULL,
  start_time TIME NOT NULL,
  end_time   TIME NOT NULL
);

CREATE TABLE slot_required_skills (
  slot_id  TINYINT NOT NULL,
  skill_id BIGINT  NOT NULL,
  PRIMARY KEY (slot_id, skill_id),
  FOREIGN KEY (slot_id)  REFERENCES slots(id),
  FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
);

-- 店舗設定（1行だけ）
CREATE TABLE store_settings (
  id                  TINYINT PRIMARY KEY DEFAULT 1,
  store_name          VARCHAR(50) NOT NULL,
  show_others_shifts  BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CHECK (id = 1)
);

-- 募集期間（前半：1〜15日、後半：16日〜末日）
CREATE TABLE shift_periods (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  start_date   DATE NOT NULL UNIQUE,
  end_date     DATE NOT NULL,
  deadline_at  DATETIME NOT NULL,
  status       ENUM('OPEN','CLOSED','PUBLISHED') NOT NULL DEFAULT 'OPEN',
  published_at DATETIME,
  CHECK (start_date <= end_date)
);

-- 必要人数：曜日ごとの既定値（day_of_week 0=日〜6=土）
CREATE TABLE required_staff_defaults (
  day_of_week    TINYINT NOT NULL,
  slot_id        TINYINT NOT NULL,
  required_count INT NOT NULL,
  PRIMARY KEY (day_of_week, slot_id),
  FOREIGN KEY (slot_id) REFERENCES slots(id),
  CHECK (day_of_week BETWEEN 0 AND 6),
  CHECK (required_count >= 0)
);

-- 必要人数：日付ごとの上書き
CREATE TABLE required_staff_overrides (
  work_date      DATE NOT NULL,
  slot_id        TINYINT NOT NULL,
  required_count INT NOT NULL,
  PRIMARY KEY (work_date, slot_id),
  FOREIGN KEY (slot_id) REFERENCES slots(id),
  CHECK (required_count >= 0)
);

-- 期間ごとの提出状況
CREATE TABLE request_submissions (
  period_id    BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  submitted_at DATETIME NOT NULL,
  PRIMARY KEY (period_id, user_id),
  FOREIGN KEY (period_id) REFERENCES shift_periods(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id)   REFERENCES users(id) ON DELETE CASCADE
);

-- シフト希望：枠ごとの○×は slot_id あり、時間指定は slot_id なし＋時刻
CREATE TABLE shift_requests (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  period_id     BIGINT NOT NULL,
  user_id       BIGINT NOT NULL,
  work_date     DATE NOT NULL,
  slot_id       TINYINT,
  availability  ENUM('OK','NG','TIME') NOT NULL,
  start_time    TIME,
  end_time      TIME,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_request (user_id, work_date, slot_id),
  FOREIGN KEY (period_id) REFERENCES shift_periods(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id)   REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (slot_id)   REFERENCES slots(id),
  CHECK ((availability = 'TIME' AND slot_id IS NULL AND start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time)
      OR (availability <> 'TIME' AND slot_id IS NOT NULL AND start_time IS NULL AND end_time IS NULL))
);

-- 割当（確定シフト）：時刻は枠からコピーして必ず持つ
CREATE TABLE shift_assignments (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  period_id   BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  work_date   DATE NOT NULL,
  slot_id     TINYINT,
  start_time  TIME NOT NULL,
  end_time    TIME NOT NULL,
  status      ENUM('ASSIGNED','ABSENT') NOT NULL DEFAULT 'ASSIGNED',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_assign_date (work_date, slot_id),
  INDEX idx_assign_user (user_id, work_date),
  FOREIGN KEY (period_id) REFERENCES shift_periods(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id)   REFERENCES users(id),
  FOREIGN KEY (slot_id)   REFERENCES slots(id),
  CHECK (start_time < end_time)
);

CREATE TABLE absences (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  assignment_id  BIGINT NOT NULL UNIQUE,
  registered_by  BIGINT NOT NULL,
  reason         VARCHAR(200),
  notice_message VARCHAR(500) NOT NULL,
  notified_at    DATETIME,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (assignment_id) REFERENCES shift_assignments(id) ON DELETE CASCADE,
  FOREIGN KEY (registered_by) REFERENCES users(id)
);

CREATE TABLE notifications (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  type       ENUM('DEADLINE','PUBLISHED','CHANGED','VACANCY','CHAT') NOT NULL,
  title      VARCHAR(100) NOT NULL,
  body       VARCHAR(500),
  link_url   VARCHAR(200),
  read_at    DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_notice_user (user_id, read_at),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 締切通知のタイミング（0=当日,1=前日,3=3日前,7=7日前）。既定は1と3
CREATE TABLE notification_settings (
  user_id     BIGINT NOT NULL,
  days_before TINYINT NOT NULL,
  PRIMARY KEY (user_id, days_before),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CHECK (days_before IN (0,1,3,7))
);

-- 外部カレンダー購読用URLのトークン
CREATE TABLE calendar_feeds (
  user_id    BIGINT PRIMARY KEY,
  token      CHAR(43) NOT NULL UNIQUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- チャット：店長とバイトの1対1。staff_user_id で会話を分ける
CREATE TABLE chat_messages (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  staff_user_id  BIGINT NOT NULL,
  sender_user_id BIGINT NOT NULL,
  body           VARCHAR(500) NOT NULL,
  read_at        DATETIME,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chat (staff_user_id, created_at),
  FOREIGN KEY (staff_user_id)  REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (sender_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE operation_logs (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT,
  action     VARCHAR(50) NOT NULL,
  target     VARCHAR(100),
  detail     VARCHAR(500),
  ip_address VARCHAR(45),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_log_time (created_at),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE backups (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  file_name  VARCHAR(200) NOT NULL,
  file_size  BIGINT,
  status     ENUM('SUCCESS','FAILED') NOT NULL,
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);
