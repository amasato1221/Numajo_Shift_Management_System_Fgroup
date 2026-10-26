-- シフト管理システム 初期データ（40_DB設計書 第1.0版）
INSERT INTO slots (id, code, name, start_time, end_time) VALUES
  (1, 'DAY',     '日勤', '08:00:00', '17:00:00'),
  (2, 'EVENING', '夕勤', '17:00:00', '22:00:00');

INSERT INTO store_settings (id, store_name, show_others_shifts) VALUES
  (1, 'セブンイレブン 三島駅西通り店', TRUE);

-- 初期管理者（初期パスワードは班の中だけで共有する。初回ログイン時に変更を強制する）
INSERT INTO users (login_id, password_hash, name, name_kana, role, must_change_password) VALUES
  ('admin', '$2a$10$nwP/4KBszvToflYP2jG7Xuot0dCbS9iyyW1nUypDuITDqBHfVvub6', '新井', 'あらい', 'ADMIN', TRUE);

-- 必要人数の曜日既定値（0=日〜6=土）。店長がSM-05で設定するまでは0人
INSERT INTO required_staff_defaults (day_of_week, slot_id, required_count) VALUES
  (0,1,0),(0,2,0),(1,1,0),(1,2,0),(2,1,0),(2,2,0),(3,1,0),(3,2,0),(4,1,0),(4,2,0),(5,1,0),(5,2,0),(6,1,0),(6,2,0);
