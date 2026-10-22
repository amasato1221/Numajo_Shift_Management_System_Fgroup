# 30-02 entity

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-02 |
| 版 | 第1.0版（令和8年10月21日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |

このページのコードは**全文**です。そのまま写してください（30-00 1章）。

## 0. entity の決まり

| 決まり | 内容 |
| --- | --- |
| Lombok | `@Getter`、`@Setter`、`@NoArgsConstructor` だけ。`@Data`・`@ToString`・`@EqualsAndHashCode` は付けない（関連をたどって無限ループになるため） |
| 関連 | `@ManyToOne`・`@OneToOne`・`@ManyToMany` はすべて `fetch = FetchType.LAZY`。一緒に使うものは repository の `JOIN FETCH` で取る（30-03） |
| DBの既定値の列 | `created_at`・`updated_at` など DB が値を入れる列は `insertable = false, updatable = false`。Java から値を入れない |
| 例外 | `CalendarFeed.createdAt` と `ShiftPeriod` の列は Java から入れる（再発行で変えるため／BT-01 で作るため） |
| 主キー | 自動採番は `GenerationType.IDENTITY`。`slots`・`store_settings` は `Byte` で、値は初期データで決まっている |
| boolean の列 | DBの `is_active` は Java では `active`（`isActive()` になる）。`@Column(name = "is_active")` で対応させる |
| ddl-auto | `spring.jpa.hibernate.ddl-auto=validate`。テーブルは Flyway が作り、Hibernate は形が合っているかだけを確かめる（起動時に合わなければエラーで止まる） |

## 1. エンティティ

### `User`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/User.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import jp.numajo.fgroup.shift.constant.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ユーザー（users）のエンティティ。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    /** 主キー（自動採番） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** ログインID */
    @Column(name = "login_id")
    private String loginId;

    /** BCryptハッシュ */
    @Column(name = "password_hash")
    private String passwordHash;

    /** 氏名 */
    @Column(name = "name")
    private String name;

    /** ふりがな */
    @Column(name = "name_kana")
    private String nameKana;

    /** 利用者の種類 */
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role;

    /** 研修中バッジ */
    @Column(name = "is_trainee")
    private boolean trainee;

    /** 有効フラグ */
    @Column(name = "is_active")
    private boolean active;

    /** 初回パスワード変更が必要か */
    @Column(name = "must_change_password")
    private boolean mustChangePassword;

    /** 連続ログイン失敗回数 */
    @Column(name = "failed_login_count")
    private int failedLoginCount;

    /** ロック解除日時 */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** 持っている職能 */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_skills",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> skills = new HashSet<>();

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

### `Skill`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/Skill.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 職能（skills）のエンティティ。
 */
@Entity
@Table(name = "skills")
@Getter
@Setter
@NoArgsConstructor
public class Skill {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 職能名 */
    @Column(name = "name")
    private String name;

    /** 説明 */
    @Column(name = "description")
    private String description;

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

### `Slot`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/Slot.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * シフト枠（slots）のエンティティ。
 */
@Entity
@Table(name = "slots")
@Getter
@Setter
@NoArgsConstructor
public class Slot {

    /** 主キー（1=日勤、2=夕勤） */
    @Id
    @Column(name = "id")
    private Byte id;

    /** コード（DAY／EVENING） */
    @Column(name = "code")
    private String code;

    /** 名称 */
    @Column(name = "name")
    private String name;

    /** 開始時刻 */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** 終了時刻 */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** この枠に必要な職能 */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "slot_required_skills",
            joinColumns = @JoinColumn(name = "slot_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> requiredSkills = new HashSet<>();
}
```

### `StoreSetting`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/StoreSetting.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 店舗設定（store_settings）のエンティティ。
 */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
@NoArgsConstructor
public class StoreSetting {

    /** 主キー（常に1） */
    @Id
    @Column(name = "id")
    private Byte id;

    /** 店舗名 */
    @Column(name = "store_name")
    private String storeName;

    /** ほかの人のシフトを表示するか */
    @Column(name = "show_others_shifts")
    private boolean showOthersShifts;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

### `ShiftPeriod`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/ShiftPeriod.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 募集期間（shift_periods）のエンティティ。
 */
@Entity
@Table(name = "shift_periods")
@Getter
@Setter
@NoArgsConstructor
public class ShiftPeriod {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 開始日 */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 終了日 */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** 提出期限 */
    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;

    /** 状態 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private PeriodStatus status;

    /** 公開日時 */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
```

### `RequiredStaffDefault`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/RequiredStaffDefault.java`

```java
package jp.numajo.fgroup.shift.entity;


import jp.numajo.fgroup.shift.entity.id.RequiredStaffDefaultId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 必要人数（曜日の既定値）（required_staff_defaults）のエンティティ。
 */
@Entity
@Table(name = "required_staff_defaults")
@Getter
@Setter
@NoArgsConstructor
public class RequiredStaffDefault {

    /** 複合主キー（曜日・枠） */
    @EmbeddedId
    private RequiredStaffDefaultId id;

    /** 必要人数 */
    @Column(name = "required_count")
    private int requiredCount;
}
```

### `RequiredStaffOverride`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/RequiredStaffOverride.java`

```java
package jp.numajo.fgroup.shift.entity;


import jp.numajo.fgroup.shift.entity.id.RequiredStaffOverrideId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 必要人数（日付ごとの上書き）（required_staff_overrides）のエンティティ。
 */
@Entity
@Table(name = "required_staff_overrides")
@Getter
@Setter
@NoArgsConstructor
public class RequiredStaffOverride {

    /** 複合主キー（日付・枠） */
    @EmbeddedId
    private RequiredStaffOverrideId id;

    /** 必要人数 */
    @Column(name = "required_count")
    private int requiredCount;
}
```

### `RequestSubmission`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/RequestSubmission.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.entity.id.RequestSubmissionId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 希望の提出状況（request_submissions）のエンティティ。
 */
@Entity
@Table(name = "request_submissions")
@Getter
@Setter
@NoArgsConstructor
public class RequestSubmission {

    /** 複合主キー（期間・ユーザー） */
    @EmbeddedId
    private RequestSubmissionId id;

    /** 提出日時 */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;
}
```

### `ShiftRequest`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/ShiftRequest.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jp.numajo.fgroup.shift.constant.Availability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * シフト希望（shift_requests）のエンティティ。
 */
@Entity
@Table(name = "shift_requests")
@Getter
@Setter
@NoArgsConstructor
public class ShiftRequest {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 募集期間 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_id")
    private ShiftPeriod period;

    /** 希望を出した人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 日付 */
    @Column(name = "work_date")
    private LocalDate workDate;

    /** 枠（時間指定のときはnull） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id")
    private Slot slot;

    /** ○・×・時間指定 */
    @Enumerated(EnumType.STRING)
    @Column(name = "availability")
    private Availability availability;

    /** 開始時刻（時間指定のみ） */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** 終了時刻（時間指定のみ） */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

### `ShiftAssignment`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/ShiftAssignment.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jp.numajo.fgroup.shift.constant.AssignmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 割当（確定シフト）（shift_assignments）のエンティティ。
 */
@Entity
@Table(name = "shift_assignments")
@Getter
@Setter
@NoArgsConstructor
public class ShiftAssignment {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 募集期間 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_id")
    private ShiftPeriod period;

    /** 割り当てた人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 日付 */
    @Column(name = "work_date")
    private LocalDate workDate;

    /** 枠（時間指定のときはnull） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id")
    private Slot slot;

    /** 開始時刻 */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** 終了時刻 */
    @Column(name = "end_time")
    private LocalTime endTime;

    /** 出勤・欠勤 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AssignmentStatus status;

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

### `Absence`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/Absence.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 欠勤（absences）のエンティティ。
 */
@Entity
@Table(name = "absences")
@Getter
@Setter
@NoArgsConstructor
public class Absence {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 欠勤になった割当 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    private ShiftAssignment assignment;

    /** 登録した店長 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by")
    private User registeredBy;

    /** 理由 */
    @Column(name = "reason")
    private String reason;

    /** 欠員通知の文面 */
    @Column(name = "notice_message")
    private String noticeMessage;

    /** 通知日時 */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

### `Notification`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/Notification.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * お知らせ（notifications）のエンティティ。
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 宛先 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 種類 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private NotificationType type;

    /** 件名 */
    @Column(name = "title")
    private String title;

    /** 本文 */
    @Column(name = "body")
    private String body;

    /** 移動先URL */
    @Column(name = "link_url")
    private String linkUrl;

    /** 既読日時 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 作成日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

### `NotificationSetting`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/NotificationSetting.java`

```java
package jp.numajo.fgroup.shift.entity;


import jp.numajo.fgroup.shift.entity.id.NotificationSettingId;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 締切通知の設定（notification_settings）のエンティティ。
 */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSetting {

    /** 複合主キー（ユーザー・何日前） */
    @EmbeddedId
    private NotificationSettingId id;
}
```

### `CalendarFeed`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/CalendarFeed.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * カレンダー購読（calendar_feeds）のエンティティ。
 */
@Entity
@Table(name = "calendar_feeds")
@Getter
@Setter
@NoArgsConstructor
public class CalendarFeed {

    /** 主キー（ユーザーID） */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /** 購読URLのトークン（43文字） */
    @Column(name = "token")
    private String token;

    /** 発行日時 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

### `ChatMessage`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/ChatMessage.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チャット（chat_messages）のエンティティ。
 */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 会話の相手のバイト */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_user_id")
    private User staffUser;

    /** 送信者 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_user_id")
    private User senderUser;

    /** 本文 */
    @Column(name = "body")
    private String body;

    /** 既読日時 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 送信日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

### `OperationLog`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/OperationLog.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 操作ログ（operation_logs）のエンティティ。
 */
@Entity
@Table(name = "operation_logs")
@Getter
@Setter
@NoArgsConstructor
public class OperationLog {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 操作した人（不明時はnull） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 操作の種類 */
    @Column(name = "action")
    private String action;

    /** 対象 */
    @Column(name = "target")
    private String target;

    /** 詳細 */
    @Column(name = "detail")
    private String detail;

    /** IPアドレス */
    @Column(name = "ip_address")
    private String ipAddress;

    /** 操作日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

### `Backup`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/Backup.java`

```java
package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;

import jp.numajo.fgroup.shift.constant.BackupStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * バックアップ履歴（backups）のエンティティ。
 */
@Entity
@Table(name = "backups")
@Getter
@Setter
@NoArgsConstructor
public class Backup {

    /** 主キー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** ファイル名 */
    @Column(name = "file_name")
    private String fileName;

    /** サイズ（バイト） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 結果 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private BackupStatus status;

    /** 作成した人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** 作成日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
```

## 2. 複合主キー

### `RequiredStaffDefaultId`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/id/RequiredStaffDefaultId.java`

```java
package jp.numajo.fgroup.shift.entity.id;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 必要人数（曜日）の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RequiredStaffDefaultId implements Serializable {

    /** 曜日（0=日〜6=土） */
    @Column(name = "day_of_week")
    private Byte dayOfWeek;

    /** 枠ID */
    @Column(name = "slot_id")
    private Byte slotId;
}
```

### `RequiredStaffOverrideId`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/id/RequiredStaffOverrideId.java`

```java
package jp.numajo.fgroup.shift.entity.id;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 必要人数（日別）の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RequiredStaffOverrideId implements Serializable {

    /** 日付 */
    @Column(name = "work_date")
    private LocalDate workDate;

    /** 枠ID */
    @Column(name = "slot_id")
    private Byte slotId;
}
```

### `RequestSubmissionId`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/id/RequestSubmissionId.java`

```java
package jp.numajo.fgroup.shift.entity.id;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 提出状況の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RequestSubmissionId implements Serializable {

    /** 募集期間ID */
    @Column(name = "period_id")
    private Long periodId;

    /** ユーザーID */
    @Column(name = "user_id")
    private Long userId;
}
```

### `NotificationSettingId`

ファイル：`src/main/java/jp/numajo/fgroup/shift/entity/id/NotificationSettingId.java`

```java
package jp.numajo.fgroup.shift.entity.id;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 締切通知設定の複合主キー。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class NotificationSettingId implements Serializable {

    /** ユーザーID */
    @Column(name = "user_id")
    private Long userId;

    /** 何日前（0・1・3・7） */
    @Column(name = "days_before")
    private Byte daysBefore;
}
```

