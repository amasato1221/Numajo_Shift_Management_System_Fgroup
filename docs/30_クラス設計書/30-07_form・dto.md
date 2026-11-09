# 30-07 form・dto

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-07 |
| 版 | 第1.3版（令和8年11月9日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |
| 1.1 | 令和8年10月22日 | 設計書評価の指摘を反映：RequestPageView に submissionState・statusLabel を追加（H2） | 新井 |
| 1.2 | 令和8年11月2日 | 実装中の質問（栗原）を反映：DayRequestForm#workDate に ISO 形式の指定を追加。RequestDayRow に dateLabel、RequestPageView に deadlineLabel・submittedAtLabel、StaffDetailView に updatedAtLabel・lockedUntilLabel を追加。StaffRowView の skillNames を職能名の順にした | 新井 |
| 1.3 | 令和8年11月9日 | 日付の表示の統一（10_基本設計書 5.3 第1.4版）：3章の「テンプレートで `#temporals.format` を使ってよい」を削除し、日付型の項目には表示用の `〜Label` を必ず並べる決まりにした。4章の dto に `〜Label` を追加し、日付の列を表す `DayColumn` を新しく作った（`ShiftTable`・`AssignmentMatrix` の `days`） | 新井 |

## 1. form の決まり

- クラスに `@Getter`、`@Setter`、`@NoArgsConstructor` を付けます（Thymeleaf と Spring のデータバインドが getter・setter を使うため、record にしない）。
- 入力チェックのメッセージは `message = "{E2201}"` の形で、93_メッセージ一覧のコードを書きます。
- リストの初期値は `new ArrayList<>()`（null にしない）。
- 例：

```java
package jp.numajo.fgroup.shift.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 職能の追加・変更フォーム（SM-04）。
 */
@Getter
@Setter
@NoArgsConstructor
public class SkillForm {

    /** 職能名 */
    @NotBlank(message = "{E2401}")
    @Size(max = 50, message = "{E2402}")
    private String name;

    /** 説明 */
    @Size(max = 200, message = "{E2403}")
    private String description;
}
```

## 2. form の一覧

| クラス | 画面 | 項目（型）とチェック |
| --- | --- | --- |
| `PasswordChangeForm` | SC-03 | `currentPassword`（String）`@NotBlank(E0301)`／`newPassword`（String）`@NotBlank(E0302)` `@Pattern(regexp="^[\\x21-\\x7E]{8,64}$", E0303)`／`confirmPassword`（String）`@NotBlank(E0304)` |
| `ShiftRequestForm` | SB-03、SM-06-1 | `periodId`（Long）`@NotNull(E1301)`／`days`（`List<DayRequestForm>`）`@Valid @NotEmpty(E1302)` |
| `DayRequestForm` | SB-03、SM-06-1 | `workDate`（LocalDate、`@DateTimeFormat(iso=ISO.DATE)`）`@NotNull(E1302)`／`mode`（RequestMode）`@NotNull(E1302)`／`dayShift`（Availability）／`eveningShift`（Availability）／`startTime`（LocalTime、`@DateTimeFormat(pattern="HH:mm")`）／`endTime`（同じ） |
| `NotificationSettingForm` | SB-05 | `daysBefore`（`List<Integer>`）チェックなし（Service で E1501） |
| `ChatForm` | SB-06、SM-11 | `body`（String）`@NotBlank(E1601)` `@Size(max=500, E1602)` |
| `StaffForm` | SM-03 | `name`（String）`@NotBlank(E2201)` `@Size(max=50, E2202)`／`nameKana`（String）`@NotBlank(E2203)` `@Size(max=50, E2204)` `@Pattern(regexp="^[ぁ-ゖー 　]+$", E2205)`／`loginId`（String）`@NotBlank(E2206)` `@Pattern(regexp="^[A-Za-z0-9]{4,20}$", E2207)`／`skillIds`（`List<Long>`）／`trainee`（boolean）／`active`（boolean、初期値 true） |
| `SkillForm` | SM-04 | 1章の例のとおり |
| `SlotSkillForm` | SM-04 | `daySkillIds`（`List<Long>`）／`eveningSkillIds`（`List<Long>`） |
| `RequiredDefaultForm` | SM-05 | `dayCounts`・`eveningCounts`（`List<@NotNull(E2501) @Min(value=0, E2502) @Max(value=20, E2502) Integer>`）`@Size(min=7, max=7, E2501)` |
| `RequiredOverrideForm` | SM-05 | `workDate`（LocalDate、`@DateTimeFormat(iso=ISO.DATE)`）`@NotNull(E2503)`／`slotId`（Byte）`@NotNull(E2504)`／`requiredCount`（Integer）`@NotNull(E2501)` `@Min(0, E2502)` `@Max(20, E2502)` |
| `AssignmentToggleForm` | SM-06 | `cell`（String）チェックなし（Controller で正規表現、E2601） |
| `TimeAssignmentForm` | SM-06 | `userId`（Long）`@NotNull(E2606)`／`workDate`（LocalDate）`@NotNull(E2606)`／`startTime`・`endTime`（LocalTime、`@DateTimeFormat(pattern="HH:mm")`）`@NotNull(E2607)` |
| `AbsenceForm` | SM-07 | `assignmentId`（Long）`@NotNull(E2701)`／`reason`（String）`@Size(max=200, E2703)`／`noticeMessage`（String）`@NotBlank(E2704)` `@Size(max=500, E2705)` |
| `StoreSettingForm` | SM-09 | `storeName`（String）`@NotBlank(E2901)` `@Size(max=50, E2902)`／`showOthersShifts`（boolean） |
| `ManagerForm` | SA-02 | `name`（String）`@NotBlank(E4201)` `@Size(max=50, E4202)`／`nameKana`（String）`@Size(max=50, E4203)` `@Pattern(regexp="^[ぁ-ゖー 　]*$", E4204)`／`loginId`（String）`@NotBlank(E4205)` `@Pattern(regexp="^[A-Za-z0-9]{4,20}$", E4206)` |
| `LogSearchForm` | SA-03 | `from`・`to`（LocalDate、`@DateTimeFormat(iso=ISO.DATE)`）／`userId`（Long）／`action`（OperationAction）／`page`（Integer）。チェックなし |

**正規表現の注意：** Java の文字列では `\` を2つ重ねます（`"^[\\x21-\\x7E]{8,64}$"`）。`@Pattern` は文字列全体に一致するかを見るので `^`・`$` はなくても同じですが、読みやすさのため付けます。

## 3. dto の決まり

- `jp.numajo.fgroup.shift.dto` に `record` で作ります。1ファイル1レコード。
- 画面で `${row.name}` のように読むので、項目名は下の表のとおりにします（テンプレートと合わせるため変えてはいけない）。
- 日付・日時・時刻の項目は `LocalDate`／`LocalDateTime`／`LocalTime`／`YearMonth` のまま持ちます（データを欠かさない。並べ替え・比較・リンクの値に使う）。
- 画面に出す日付の文字は、**その項目のすぐ後ろに `〜Label`（`String`）を置き**、Service が `DateTimeUtil` で作って入れます（例：`LocalDateTime createdAt, String createdAtLabel`）。どの形式にするかは下の表の「Label の形式」の列のとおりです（10_基本設計書 5.3 の表の名前）。
- テンプレートは `〜Label` を出すだけにし、日付の文字を組み立ててはいけません（`#temporals`・`#dates`・`T(…DateTimeUtil)` も使わない。91_コーディング規約 5.1）。
- 値が null のとき（未提出・未通知など）は `〜Label` も null にし、テンプレートで `th:if` を使って出し分けます。

## 4. dto の一覧

| record | 項目（順番どおり） | 作る Service |
| --- | --- | --- |
| `SelectOption` | `String value, String label` | 各 Service（プルダウン） |
| `PeriodOption` | `Long id, LocalDate startDate, LocalDate endDate, String periodLabel, LocalDateTime deadlineAt, String deadlineLabel, String deadlineShortLabel, PeriodStatus status, String label`（`periodLabel`＝募集期間、`deadlineLabel`＝日時、`deadlineShortLabel`＝日時（短い）。`label` はプルダウンの表示名で、30-05 の PeriodService のとおり） | `PeriodService#toOption` |
| `NoticeRow` | `Long id, NotificationType type, String typeLabel, String title, String body, LocalDateTime createdAt, String createdAtLabel, boolean unread`（`createdAtLabel`＝日時） | `NotificationService` |
| `ShiftBadge` | `BadgeType type, String icons, String timeText, LocalTime startTime, LocalTime endTime` | `ShiftCalendarService` |
| `CalendarDay` | `LocalDate date, String dayLabel, boolean inMonth, boolean today, List<ShiftBadge> badges, Long periodId`（`dayLabel`＝日だけ） | `ShiftCalendarService` |
| `MonthCalendar` | `YearMonth ym, String ymLabel, List<List<CalendarDay>> weeks, YearMonth prevYm, YearMonth nextYm`（`ymLabel`＝年月） | `ShiftCalendarService` |
| `PeriodCalendar` | `PeriodOption period, List<List<CalendarDay>> weeks` | `ShiftCalendarService` |
| `NextShiftView` | `LocalDate workDate, String workDateLabel, ShiftBadge badge, LocalTime startTime, LocalTime endTime, String timeLabel`（`workDateLabel`＝日付（月日と曜日）、`timeLabel`＝時間帯） | `StaffHomeService` |
| `StaffHomeView` | `NextShiftView nextShift, List<PeriodOption> unsubmittedPeriods, List<NoticeRow> latestNotices, MonthCalendar calendar` | `StaffHomeService` |
| `RequestDayRow` | `LocalDate workDate, String dateLabel, boolean dayQualified, boolean eveningQualified`（`dateLabel`＝日付（月日と曜日）） | `ShiftRequestService` |
| `RequestPageView` | `PeriodOption period, List<PeriodOption> periodOptions, String deadlineLabel, boolean editable, LocalDateTime submittedAt, String submittedAtLabel, SubmissionState submissionState, String statusLabel, List<RequestDayRow> days, String staffName`（`deadlineLabel`・`submittedAtLabel`＝日時） | `ShiftRequestService` |
| `ShiftTableRow` | `Long userId, String name, boolean trainee, Map<LocalDate, List<ShiftBadge>> cells, int workDays` | `ShiftTableService` |
| `DayColumn` | `LocalDate date, String dateLabel, String dayLabel, String dayOfWeekLabel, String dateWithDayLabel`（表の日付の列。`dateLabel`＝日付（短い）、`dayLabel`＝日だけ、`dayOfWeekLabel`＝曜日だけ、`dateWithDayLabel`＝月日と曜日（短い）。セルは `row.cells[col.date]` で引く） | `ShiftTableService`、`AssignmentService` |
| `ShiftTable` | `List<DayColumn> days, List<ShiftTableRow> rows` | `ShiftTableService` |
| `ChatMessageView` | `Long id, String senderName, String body, LocalDateTime createdAt, String createdAtLabel, boolean mine`（`createdAtLabel`＝日時（短い）） | `ChatService` |
| `ChatListRow` | `Long staffId, String initial, String staffName, boolean active, String lastBody, LocalDateTime lastAt, String lastAtLabel, long unreadCount`（`lastAtLabel`＝今日なら時刻、今年なら日付（短い）、それより前は日付（短い・年付き）。20-SM-10） | `ChatService` |
| `SubmissionStatusView` | `PeriodOption period, int submitted, int total, List<String> unsubmittedNames` | `DashboardService` |
| `ShortageView` | `Long periodId, LocalDate workDate, String workDateLabel, String slotName, int assigned, int required`（`workDateLabel`＝日付（月日と曜日）） | `DashboardService` |
| `VacancyView` | `LocalDate workDate, String workDateLabel, String slotLabel, String staffName, LocalDateTime notifiedAt, String notifiedAtLabel, int assigned, int required`（`workDateLabel`＝日付（月日と曜日）、`notifiedAtLabel`＝日時（短い）） | `DashboardService` |
| `PeriodRowView` | `PeriodOption period, int submitted, int total` | `DashboardService` |
| `DashboardView` | `List<SubmissionStatusView> submissionStatuses, List<ShortageView> shortages, int moreShortages, List<VacancyView> vacancies, List<PeriodRowView> periods` | `DashboardService` |
| `StaffRowView` | `Long id, String name, String nameKana, String loginId, List<String> skillNames, boolean trainee, boolean active, boolean locked`（`skillNames` は職能名の順） | `StaffService` |
| `StaffDetailView` | `Long id, LocalDateTime updatedAt, String updatedAtLabel, boolean locked, LocalDateTime lockedUntil, String lockedUntilLabel`（`updatedAtLabel`＝日時、`lockedUntilLabel`＝時刻。ロック中でなければ null） | `StaffService` |
| `StaffCreatedResult` | `Long userId, String initialPassword` | `StaffService` |
| `SkillRowView` | `Long id, String name, String description, long holderCount` | `SkillService` |
| `RequiredDayView` | `LocalDate workDate, String workDateLabel, int dayCount, boolean dayOverridden, int eveningCount, boolean eveningOverridden`（`workDateLabel`＝日付（月日と曜日）） | `RequiredStaffService` |
| `ShiftWarning` | `WarningType type, LocalDate workDate, String workDateLabel, Byte slotId, String messageCode, Object[] args`（`workDateLabel`＝日付（月日と曜日）。`args` の日付も同じ文字列） | `ShiftWarningService` |
| `MatrixCell` | `RequestMark request, String requestTitle, AssignMark assign, Long assignmentId, String confirmCode, boolean disabled` | `AssignmentService` |
| `MatrixRow` | `Long userId, String name, boolean trainee, boolean submitted, Map<LocalDate, Map<Byte, MatrixCell>> cells` | `AssignmentService` |
| `CountCell` | `int assigned, int required, boolean shortage, boolean traineeOnly` | `AssignmentService` |
| `AssignmentMatrix` | `List<DayColumn> days, List<Slot> slots, List<MatrixRow> rows, Map<LocalDate, Map<Byte, CountCell>> counts` | `AssignmentService` |
| `TimeRequestRow` | `Long userId, String name, LocalDate workDate, String workDateLabel, LocalTime startTime, LocalTime endTime, String timeLabel, boolean assigned`（`workDateLabel`＝日付（月日と曜日）、`timeLabel`＝時間帯） | `AssignmentService` |
| `TimeAssignmentRow` | `Long assignmentId, String name, LocalDate workDate, String workDateLabel, LocalTime startTime, LocalTime endTime, String timeLabel, AssignmentStatus status`（同上） | `AssignmentService` |
| `PeriodPageView` | `PeriodOption period, LocalDateTime publishedAt, String publishedAtLabel, List<PeriodOption> periodOptions, AssignmentMatrix matrix, List<ShiftWarning> warnings, List<TimeRequestRow> timeRequests, List<TimeAssignmentRow> timeAssignments, boolean editable, boolean publishable, String notPublishableCode`（`publishedAtLabel`＝日時。未公開なら null） | `AssignmentService` |
| `PublishResult` | `int assignmentCount, boolean hasWarnings` | `AssignmentService` |
| `AbsenceTargetRow` | `Long assignmentId, String slotLabel, String staffName, LocalDateTime registeredAt, String registeredAtLabel, LocalDateTime notifiedAt, String notifiedAtLabel`（どちらも日時。`slotLabel` の時間指定は時間帯） | `AbsenceService` |
| `AbsenceDayView` | `LocalDate date, String dateLabel, List<AbsenceTargetRow> candidatesOfDay, List<AbsenceTargetRow> absentsOfDay`（`dateLabel`＝日付（月日と曜日）） | `AbsenceService` |
| `VacancyRecipientRow` | `Long userId, String name, boolean trainee` | `AbsenceService` |
| `AbsenceConfirmView` | `Long assignmentId, LocalDate workDate, String workDateLabel, String targetLabel, List<VacancyRecipientRow> recipients, String defaultMessage`（`workDateLabel`＝日付（月日と曜日）） | `AbsenceService` |
| `ManagerRow` | `Long id, String name, String loginId, boolean active, boolean locked, LocalDateTime createdAt, String createdAtLabel, boolean lastActive`（`createdAtLabel`＝日付（年月日）） | `ManagerAccountService` |
| `ManagerCountView` | `long active, long stopped` | `ManagerAccountService` |
| `OperationLogRow` | `Long id, LocalDateTime createdAt, String createdAtLabel, String userName, String actionLabel, String target, String detail, String ipAddress`（`createdAtLabel`＝日時（秒まで）） | `OperationLogService` |
| `BackupRow` | `Long id, LocalDateTime createdAt, String createdAtLabel, String fileName, Long fileSize, BackupStatus status, String createdByName`（`createdAtLabel`＝日時） | `BackupService` |
| `BackupResult` | `BackupStatus status, String fileName` | `BackupService` |
| `ProcessResult` | `int exitCode, boolean timedOut, String output` | `ProcessRunner` |

### 4.1 `MatrixCell` の中の列挙型

`MatrixCell` の中に入れ子の enum として書きます（`MatrixCell.RequestMark`）。

| enum | 値 | 画面の記号（20-SM-06 2.1） |
| --- | --- | --- |
| `RequestMark` | `OK`、`NG`、`TIME`、`NOT_SUBMITTED`、`NO_SKILL` | ○、×、時、・、－ |
| `AssignMark` | `NONE`、`SLOT`、`TIME`、`ABSENT` | 空、●、◐、欠 |

`confirmCode` はボタンの `data-confirm` に入れるメッセージコード（C2602〜C2604、なければ null）。テンプレートで `th:attr="data-confirm=${cell.confirmCode != null} ? #{${cell.confirmCode}} : null"` と書きます。

### 4.2 `BackupRow` の表示用メソッド

```java
/** 大きさを KB（小数1けた）で返す。失敗したときは「－」。 */
public String sizeText() {
    return fileSize == null ? "－" : String.format("%.1f KB", fileSize / 1024.0);
}
```
