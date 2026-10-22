# 30-01 起動クラス・config・constant

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-01 |
| 版 | 第1.0版（令和8年10月21日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |

このページのコードは**全文**です。そのまま写してください（30-00 1章）。

## 1. 起動クラス

`static` ブロックでタイムゾーンを日本時間に固定します。これがないと、JVMのタイムゾーンが日本以外のPC（テスト用のサーバーなど）で、`LocalTime` をDBに入れるときに9時間ずれ、`shift_assignments` の CHECK 制約（開始＜終了）に違反します（検証で実際に起きたため入れています）。JDBCのURLにも `connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true` を付けます（10_基本設計書 8章）。

### `ShiftManagementApplication`

ファイル：`src/main/java/jp/numajo/fgroup/shift/ShiftManagementApplication.java`

```java
package jp.numajo.fgroup.shift;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * シフト管理システムの起動クラス。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ShiftManagementApplication {

    static {
        // JVMの既定のタイムゾーンを日本時間に固定する（時刻をDBへ入れるときに9時間ずれるのを防ぐ。テストでも効く）
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ShiftManagementApplication.class, args);
    }
}
```

## 2. config

`SecurityConfig` は 30-04 に載せています。`AppProperties` は起動クラスの `@ConfigurationPropertiesScan` で読み込まれます。

### `ClockConfig`

ファイル：`src/main/java/jp/numajo/fgroup/shift/config/ClockConfig.java`

```java
package jp.numajo.fgroup.shift.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 現在日時を取るための Clock。テストでは固定した Clock に差し替える。
 */
@Configuration
public class ClockConfig {

    /** 日本時間の Clock */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Tokyo"));
    }
}
```

### `AppProperties`

ファイル：`src/main/java/jp/numajo/fgroup/shift/config/AppProperties.java`

```java
package jp.numajo.fgroup.shift.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * application.yml の app.* の設定。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 購読URLの組み立てに使うURL（末尾の / なし） */
    private String baseUrl = "http://localhost:8080";

    /** バックアップの設定 */
    private Backup backup = new Backup();

    /**
     * app.backup.* の設定。
     */
    @Getter
    @Setter
    public static class Backup {
        /** mysqldump.exe の場所 */
        private String mysqldumpPath;
        /** 保存先フォルダ */
        private String dir;
        /** 待つ秒数 */
        private int timeoutSeconds = 120;
    }
}
```

## 3. constant

DBの ENUM 列と同じ値の列挙型は、entity で `@Enumerated(EnumType.STRING)` にして使います（順番の数字で保存してはいけません）。

### `Role`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/Role.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * 利用者の種類。
 */
public enum Role {
    ADMIN, MANAGER, STAFF
}
```

### `Availability`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/Availability.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * シフト希望の種類（○・×・時間指定）。
 */
public enum Availability {
    OK, NG, TIME
}
```

### `RequestMode`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/RequestMode.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * 1日の希望の出し方（枠ごとに○×／時間指定）。DBには保存しない（画面のフォームだけで使う）。
 */
public enum RequestMode {
    SLOT, TIME
}
```

### `PeriodStatus`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/PeriodStatus.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * 募集期間の状態（受付中・作成中・公開済み）。
 */
public enum PeriodStatus {
    OPEN, CLOSED, PUBLISHED
}
```

### `AssignmentStatus`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/AssignmentStatus.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * 割当の状態（出勤・欠勤）。
 */
public enum AssignmentStatus {
    ASSIGNED, ABSENT
}
```

### `NotificationType`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/NotificationType.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * お知らせの種類。
 */
public enum NotificationType {
    DEADLINE, PUBLISHED, CHANGED, VACANCY, CHAT
}
```

### `BackupStatus`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/BackupStatus.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * バックアップの結果。
 */
public enum BackupStatus {
    SUCCESS, FAILED
}
```

### `WarningType`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/WarningType.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * シフト作成の警告の種類。並び順（ordinal）は画面の並び順と同じ。
 */
public enum WarningType {
    /** 研修中の人だけ（公開できない） */
    TRAINEE_ONLY,
    /** 人数不足 */
    SHORTAGE,
    /** 職能不足 */
    SKILL
}
```

### `BadgeType`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/BadgeType.java`

```java
package jp.numajo.fgroup.shift.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * シフトバッジの種類と CSS クラス（20-00 4章）。
 */
@Getter
@RequiredArgsConstructor
public enum BadgeType {
    /** 公開済みの日勤 */
    DAY("badge-day"),
    /** 公開済みの夕勤 */
    EVENING("badge-evening"),
    /** 公開済みの時間指定 */
    TIME("badge-time"),
    /** 未公開の期間で、自分が○か時間指定を出している */
    PENDING("badge-pending"),
    /** 欠勤 */
    ABSENT("badge-absent");

    /** テンプレートで class に入れる値 */
    private final String cssClass;
}
```

### `StaffStatusFilter`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/StaffStatusFilter.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * スタッフ一覧（SM-02）の状態の絞り込み。
 */
public enum StaffStatusFilter {
    ALL, ACTIVE, INACTIVE;

    /** 文字列から変換する。null・不正な値は ACTIVE。 */
    public static StaffStatusFilter parse(String value) {
        if (value != null) {
            for (StaffStatusFilter f : values()) {
                if (f.name().equals(value)) {
                    return f;
                }
            }
        }
        return ACTIVE;
    }

    /** UserRepository#searchStaff に渡す値（ALL は null）。 */
    public Boolean toActiveParam() {
        return switch (this) {
            case ALL -> null;
            case ACTIVE -> Boolean.TRUE;
            case INACTIVE -> Boolean.FALSE;
        };
    }
}
```

### `SlotIds`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/SlotIds.java`

```java
package jp.numajo.fgroup.shift.constant;

/**
 * 枠のID（slots.id）。枠は固定の2つだけ。
 */
public final class SlotIds {

    /** 日勤 8:00〜17:00 */
    public static final byte DAY = 1;
    /** 夕勤 17:00〜22:00 */
    public static final byte EVENING = 2;

    private SlotIds() {
    }
}
```

### `OperationAction`

ファイル：`src/main/java/jp/numajo/fgroup/shift/constant/OperationAction.java`

```java
package jp.numajo.fgroup.shift.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 操作ログに記録する操作の種類（94_セキュリティ設計書 4章）。
 */
@Getter
@RequiredArgsConstructor
public enum OperationAction {
    LOGIN_SUCCESS("ログイン"),
    LOGIN_FAILURE("ログイン失敗"),
    LOGIN_LOCKED("アカウントのロック"),
    LOGOUT("ログアウト"),
    PASSWORD_CHANGE("パスワード変更"),
    STAFF_CREATE("スタッフの登録"),
    STAFF_UPDATE("スタッフの変更"),
    STAFF_PASSWORD_RESET("スタッフのパスワード初期化"),
    MANAGER_CREATE("店長アカウントの作成"),
    MANAGER_STOP("店長アカウントの停止"),
    MANAGER_RESUME("店長アカウントの再開"),
    MANAGER_PASSWORD_RESET("店長のパスワード初期化"),
    SKILL_CREATE("職能の追加"),
    SKILL_UPDATE("職能の変更"),
    SKILL_DELETE("職能の削除"),
    SLOT_SKILL_UPDATE("枠の必要職能の変更"),
    REQUIRED_UPDATE("必要人数の変更"),
    REQUEST_PROXY_UPDATE("希望の代理修正"),
    ASSIGNMENT_ADD("割当の追加"),
    ASSIGNMENT_REMOVE("割当の削除"),
    SHIFT_PUBLISH("シフトの公開"),
    ABSENCE_REGISTER("欠勤登録"),
    STORE_SETTING_UPDATE("店舗設定の変更"),
    BACKUP_CREATE("バックアップ作成");

    /** 画面に出す日本語名 */
    private final String label;
}
```

