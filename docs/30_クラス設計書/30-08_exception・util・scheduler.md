# 30-08 exception・util・scheduler

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-08 |
| 版 | 第1.0版（令和8年10月21日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |

このページの exception と util のコードは**全文**です（scheduler は 4章の形で書きます）。そのまま写してください（30-00 1章）。

## 0. 動作の確認

検証用のプロジェクトで、util・exception・constant・security の各パッケージの**分岐網羅が100%（124／124）**、命令網羅は使われる前の列挙型3つ（`BadgeType`・`WarningType`・`RequestMode`）を除いて100%であることを JaCoCo で確かめました（`UtilTests` 24件、`ProcessRunnerTests` 5件、`ExceptionTests` 2件、`ConstantTests` 3件）。

条件の組み合わせを確かめたテストの例：

| 対象 | 組み合わせ |
| --- | --- |
| `ShiftTimeUtil#overlaps` | `aStart < bEnd` と `bStart < aEnd` の真偽4通り（境界で接する2通りを含む） |
| `ShiftTimeUtil#isOnGrid` | 下限の前・ちょうど、上限のちょうど・後、分が刻み外、秒・ナノ秒が0でない、null |
| `PasswordGenerator#isValid` | 英字あり・なし × 数字あり・なし の4通り＋記号だけ |
| `IcsUtil#fold` | 75バイトちょうど、76バイト、日本語（3バイト文字）の境目 |
| `ProcessRunner#run` | 成功、終了コード0以外、コマンドなし、時間切れ、割り込み |

## 1. exception

### `BusinessException`

ファイル：`src/main/java/jp/numajo/fgroup/shift/exception/BusinessException.java`

```java
package jp.numajo.fgroup.shift.exception;

import lombok.Getter;

/**
 * 業務エラー。コントローラーで受け取り、メッセージコードを画面に出す。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 93_メッセージ一覧のコード（例：E1303） */
    private final String messageCode;
    /** メッセージの引数 */
    private final transient Object[] args;

    public BusinessException(String messageCode, Object... args) {
        super(messageCode);
        this.messageCode = messageCode;
        this.args = args;
    }
}
```

### `ResourceNotFoundException`

ファイル：`src/main/java/jp/numajo/fgroup/shift/exception/ResourceNotFoundException.java`

```java
package jp.numajo.fgroup.shift.exception;

/**
 * 対象が見つからない（または自分のものでない）。404 画面を出す。
 */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

### `GlobalExceptionHandler`

ファイル：`src/main/java/jp/numajo/fgroup/shift/exception/GlobalExceptionHandler.java`

```java
package jp.numajo.fgroup.shift.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * 例外を受け取り、エラー画面（ER-01・ER-02）を出す。
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /** 対象が見つからない・URLの形が違う → 404 */
    @ExceptionHandler({ ResourceNotFoundException.class, NoResourceFoundException.class,
            MethodArgumentTypeMismatchException.class })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(Exception e) {
        log.debug("404: {}", e.getMessage());
        return "error/404";
    }

    /** 予期しないエラー → 500 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception e) {
        log.error("予期しないエラー", e);
        return "error/500";
    }
}
```

## 2. util

`ProcessResult` は dto パッケージの record ですが、`ProcessRunner` と一緒に使うためここに載せます。

### `ShiftTimeUtil`

ファイル：`src/main/java/jp/numajo/fgroup/shift/util/ShiftTimeUtil.java`

```java
package jp.numajo.fgroup.shift.util;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * シフトの時刻に関する共通処理。
 */
public final class ShiftTimeUtil {

    /** 時間指定の最小時刻 */
    public static final LocalTime MIN_TIME = LocalTime.of(8, 0);
    /** 時間指定の最大時刻 */
    public static final LocalTime MAX_TIME = LocalTime.of(22, 0);
    /** 時刻の刻み（分） */
    public static final int STEP_MINUTES = 30;

    private static final List<LocalTime> TIME_OPTIONS;

    static {
        List<LocalTime> list = new ArrayList<>();
        for (LocalTime t = MIN_TIME; !t.isAfter(MAX_TIME); t = t.plusMinutes(STEP_MINUTES)) {
            list.add(t);
        }
        TIME_OPTIONS = Collections.unmodifiableList(list);
    }

    private ShiftTimeUtil() {
    }

    /** 時刻プルダウンの選択肢（8:00〜22:00 の30分刻み、29個）。 */
    public static List<LocalTime> timeOptions() {
        return TIME_OPTIONS;
    }

    /** 8:00〜22:00 の範囲で、分が 0 か 30、秒が 0 なら true。null は false。 */
    public static boolean isOnGrid(LocalTime time) {
        return time != null
                && !time.isBefore(MIN_TIME) && !time.isAfter(MAX_TIME)
                && time.getMinute() % STEP_MINUTES == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }

    /** 2つの時間帯が重なるか（終わりと始まりが同じ時刻は重ならない）。 */
    public static boolean overlaps(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
}
```

### `DateTimeUtil`

ファイル：`src/main/java/jp/numajo/fgroup/shift/util/DateTimeUtil.java`

```java
package jp.numajo.fgroup.shift.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 日付・時刻の表示形式（10_基本設計書 5.3）。
 */
public final class DateTimeUtil {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPANESE);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPANESE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d");

    private DateTimeUtil() {
    }

    /** 例：10月25日(土) */
    public static String monthDay(LocalDate date) {
        return date.format(MONTH_DAY);
    }

    /** 例：2026年10月25日 */
    public static String fullDate(LocalDate date) {
        return date.format(FULL_DATE);
    }

    /** 例：2026/10/31 23:59 */
    public static String dateTime(LocalDateTime dateTime) {
        return dateTime.format(DATE_TIME);
    }

    /** 例：8:00、13:30 */
    public static String time(LocalTime time) {
        return time.format(TIME);
    }

    /** 例：11/1〜11/15 */
    public static String period(LocalDate start, LocalDate end) {
        return start.format(SHORT_DATE) + "〜" + end.format(SHORT_DATE);
    }

    /** 時間指定のバッジ。例：13-22、13:30-22、8-12:30 */
    public static String shortRange(LocalTime start, LocalTime end) {
        return shortTime(start) + "-" + shortTime(end);
    }

    private static String shortTime(LocalTime t) {
        return t.getMinute() == 0 ? String.valueOf(t.getHour()) : t.format(TIME);
    }
}
```

### `LikeUtil`

ファイル：`src/main/java/jp/numajo/fgroup/shift/util/LikeUtil.java`

```java
package jp.numajo.fgroup.shift.util;

/**
 * LIKE 検索の共通処理。エスケープ文字は「!」（JPQL で ESCAPE '!' と書く）。
 */
public final class LikeUtil {

    private LikeUtil() {
    }

    /** 「!」「%」「_」の前に「!」を付ける。null は null。 */
    public static String escape(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** 部分一致のパターン「%…%」を作る。null・空は null（絞り込まない）。 */
    public static String contains(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        return "%" + escape(keyword) + "%";
    }
}
```

### `IcsUtil`

ファイル：`src/main/java/jp/numajo/fgroup/shift/util/IcsUtil.java`

```java
package jp.numajo.fgroup.shift.util;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * iCalendar（RFC 5545）の文字列を作る共通処理（IC-01）。
 */
public final class IcsUtil {

    /** 改行（iCalendar は CRLF） */
    public static final String CRLF = "\r\n";
    /** 1行の最大バイト数 */
    static final int MAX_LINE_BYTES = 75;

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private IcsUtil() {
    }

    /** 「\」「;」「,」改行をエスケープする。null は空文字。 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** 75バイトを超える行を「CRLF＋半角スペース」で折り返す。文字の途中では切らない。 */
    public static String fold(String line) {
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        int limit = MAX_LINE_BYTES;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int len = ch.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > limit) {
                sb.append(CRLF).append(' ');
                bytes = 1;
                limit = MAX_LINE_BYTES;
            }
            sb.append(ch);
            bytes += len;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 日本時間の日付と時刻を、UTC の「yyyyMMdd'T'HHmmss'Z'」にする。 */
    public static String utc(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(JST).withZoneSameInstant(ZoneOffset.UTC).format(UTC_FORMAT);
    }
}
```

### `PasswordGenerator`

ファイル：`src/main/java/jp/numajo/fgroup/shift/util/PasswordGenerator.java`

```java
package jp.numajo.fgroup.shift.util;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * 初期パスワードを作る（見まちがえやすい I・O・l・0・1 を使わない）。
 */
@Component
public class PasswordGenerator {

    /** 長さ */
    public static final int LENGTH = 10;
    /** 使う文字 */
    static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final SecureRandom random = new SecureRandom();

    /** 英字と数字を両方1文字以上含む10文字を返す。 */
    public String generate() {
        while (true) {
            StringBuilder sb = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
            }
            String s = sb.toString();
            if (isValid(s)) {
                return s;
            }
        }
    }

    /** 英字と数字を両方含むか。 */
    static boolean isValid(String s) {
        boolean letter = false;
        boolean digit = false;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                digit = true;
            } else if (Character.isLetter(c)) {
                letter = true;
            }
        }
        return letter && digit;
    }
}
```

### `TokenGenerator`

ファイル：`src/main/java/jp/numajo/fgroup/shift/util/TokenGenerator.java`

```java
package jp.numajo.fgroup.shift.util;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * カレンダー購読URLのトークンを作る（32バイトの乱数を Base64URL にした43文字）。
 */
@Component
public class TokenGenerator {

    /** 乱数のバイト数 */
    static final int BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] b = new byte[BYTES];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
```

### `ProcessRunner`

ファイル：`src/main/java/jp/numajo/fgroup/shift/util/ProcessRunner.java`

```java
package jp.numajo.fgroup.shift.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import jp.numajo.fgroup.shift.dto.ProcessResult;

/**
 * 外部のコマンド（mysqldump）を実行する。BackupService のテストでモックにするため、ProcessBuilder を包む。
 */
@Component
public class ProcessRunner {

    /**
     * コマンドを実行し、終わるか時間切れになるまで待つ。
     *
     * @param command コマンドと引数（1つずつ分けたリスト。文字列をつなげない）
     * @param env     足す環境変数
     * @param timeout 待つ時間
     * @return 終了コード・時間切れか・出力（標準出力と標準エラーをまとめたもの）
     */
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // コマンドが見つからないなど
            return new ProcessResult(-1, false, e.getMessage());
        }
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(-1, true, "");
            }
            return new ProcessResult(process.exitValue(), false, output.join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProcessResult(-1, false, "interrupted");
        }
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

### `ProcessResult`

ファイル：`src/main/java/jp/numajo/fgroup/shift/dto/ProcessResult.java`

```java
package jp.numajo.fgroup.shift.dto;

/**
 * 外部コマンドの実行結果。
 *
 * @param exitCode 終了コード（起動できない・時間切れは -1）
 * @param timedOut 時間切れになったか
 * @param output   出力
 */
public record ProcessResult(int exitCode, boolean timedOut, String output) {
}
```

## 3. ProcessRunner を Windows で使うときの注意

- mysqldump のパスは `app.backup.mysqldump-path`（例：`C:/Program Files/MySQL/MySQL Server 8.4/bin/mysqldump.exe`）。空白を含むパスでも、引数をリストで渡すので `"` で囲む必要はありません。
- テスト（`ProcessRunnerTests`）は `sh` を使うため、Windows では `cmd /c` に読み替えたテストを `@EnabledOnOs(OS.WINDOWS)` で別に作ります（97_テスト計画書 4章）。

## 4. scheduler

定期処理の中身は Service に書き、scheduler のクラスは「いつ呼ぶか」だけを書きます。起動時にも1回動かします（PCを夜に切っていて 0:05 に動かなかった日のため）。

```java
package jp.numajo.fgroup.shift.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jp.numajo.fgroup.shift.service.PeriodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 募集期間の作成（BT-01）と締切（BT-02）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeriodScheduler {

    private final PeriodService periodService;
    private final Clock clock;

    /** 起動したとき：締切 → 作成 の順に1回動かす。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        closeExpired();
        createUpcoming();
    }

    /** BT-01：毎日 0:05 */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Tokyo")
    public void createUpcoming() {
        int count = periodService.createUpcomingPeriods(LocalDate.now(clock));
        log.info("BT-01 募集期間を{}件作成しました", count);
    }

    /** BT-02：毎日 0:00:30 */
    @Scheduled(cron = "30 0 0 * * *", zone = "Asia/Tokyo")
    public void closeExpired() {
        int count = periodService.closeExpiredPeriods(LocalDateTime.now(clock));
        log.info("BT-02 募集期間を{}件締め切りました", count);
    }
}
```

```java
package jp.numajo.fgroup.shift.scheduler;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jp.numajo.fgroup.shift.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 締切が近い人へのお知らせ（BT-03）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadlineNotificationScheduler {

    private final NotificationService notificationService;
    private final Clock clock;

    /** BT-03：毎日 9:00。起動時には動かさない（9:00 前に起動すると二重にならないよう、Service 側でも同じ日の送信済みを確かめる）。 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    public void sendReminders() {
        int count = notificationService.sendDeadlineReminders(LocalDate.now(clock));
        log.info("BT-03 締切のお知らせを{}件作成しました", count);
    }
}
```

- scheduler の中で例外が起きても、次の日には動きます（Spring がログに出して続ける）。例外を握りつぶす `try-catch` は書きません。
- テストでは `@MockitoBean` で Service をモックにし、`onStartup()` が `closeExpiredPeriods` → `createUpcomingPeriods` の順に呼ぶことだけを確かめます。
