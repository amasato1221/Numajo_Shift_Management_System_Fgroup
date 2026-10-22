# 30-07 form・dto

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-07 |
| 版 | 第1.1版（令和8年10月22日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |
| 1.1 | 令和8年10月22日 | 設計書評価の指摘を反映：RequestPageView に submissionState・statusLabel を追加（H2） | 新井 |

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
| `DayRequestForm` | SB-03、SM-06-1 | `workDate`（LocalDate）`@NotNull(E1302)`／`mode`（RequestMode）`@NotNull(E1302)`／`dayShift`（Availability）／`eveningShift`（Availability）／`startTime`（LocalTime、`@DateTimeFormat(pattern="HH:mm")`）／`endTime`（同じ） |
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
- 表示用の文字列（「11/1〜11/15」など）は、Service で `DateTimeUtil` を使って作って入れます。テンプレートで日付を組み立てる場合は `#temporals.format(date, 'M/d')` を使ってよい。

## 4. dto の一覧

| record | 項目（順番どおり） | 作る Service |
| --- | --- | --- |
| `SelectOption` | `String value, String label` | 各 Service（プルダウン） |
| `PeriodOption` | `Long id, LocalDate startDate, LocalDate endDate, LocalDateTime deadlineAt, PeriodStatus status, String label` | `PeriodService#toOption` |
| `NoticeRow` | `Long id, NotificationType type, String typeLabel, String title, String body, LocalDateTime createdAt, boolean unread` | `NotificationService` |
| `ShiftBadge` | `BadgeType type, String icons, String timeText, LocalTime startTime, LocalTime endTime` | `ShiftCalendarService` |
| `CalendarDay` | `LocalDate date, boolean inMonth, boolean today, List<ShiftBadge> badges, Long periodId` | `ShiftCalendarService` |
| `MonthCalendar` | `YearMonth ym, List<List<CalendarDay>> weeks, YearMonth prevYm, YearMonth nextYm` | `ShiftCalendarService` |
| `PeriodCalendar` | `PeriodOption period, List<List<CalendarDay>> weeks` | `ShiftCalendarService` |
| `NextShiftView` | `LocalDate workDate, ShiftBadge badge, LocalTime startTime, LocalTime endTime` | `StaffHomeService` |
| `StaffHomeView` | `NextShiftView nextShift, List<PeriodOption> unsubmittedPeriods, List<NoticeRow> latestNotices, MonthCalendar calendar` | `StaffHomeService` |
| `RequestDayRow` | `LocalDate workDate, boolean dayQualified, boolean eveningQualified` | `ShiftRequestService` |
| `RequestPageView` | `PeriodOption period, List<PeriodOption> periodOptions, boolean editable, LocalDateTime submittedAt, SubmissionState submissionState, String statusLabel, List<RequestDayRow> days, String staffName` | `ShiftRequestService` |
| `ShiftTableRow` | `Long userId, String name, boolean trainee, Map<LocalDate, List<ShiftBadge>> cells, int workDays` | `ShiftTableService` |
| `ShiftTable` | `List<LocalDate> days, List<ShiftTableRow> rows` | `ShiftTableService` |
| `ChatMessageView` | `Long id, String senderName, String body, LocalDateTime createdAt, boolean mine` | `ChatService` |
| `ChatListRow` | `Long staffId, String initial, String staffName, boolean active, String lastBody, LocalDateTime lastAt, long unreadCount` | `ChatService` |
| `SubmissionStatusView` | `PeriodOption period, int submitted, int total, List<String> unsubmittedNames` | `DashboardService` |
| `ShortageView` | `Long periodId, LocalDate workDate, String slotName, int assigned, int required` | `DashboardService` |
| `VacancyView` | `LocalDate workDate, String slotLabel, String staffName, LocalDateTime notifiedAt, int assigned, int required` | `DashboardService` |
| `PeriodRowView` | `PeriodOption period, int submitted, int total` | `DashboardService` |
| `DashboardView` | `List<SubmissionStatusView> submissionStatuses, List<ShortageView> shortages, int moreShortages, List<VacancyView> vacancies, List<PeriodRowView> periods` | `DashboardService` |
| `StaffRowView` | `Long id, String name, String nameKana, String loginId, List<String> skillNames, boolean trainee, boolean active, boolean locked` | `StaffService` |
| `StaffDetailView` | `Long id, LocalDateTime updatedAt, boolean locked, LocalDateTime lockedUntil` | `StaffService` |
| `StaffCreatedResult` | `Long userId, String initialPassword` | `StaffService` |
| `SkillRowView` | `Long id, String name, String description, long holderCount` | `SkillService` |
| `RequiredDayView` | `LocalDate workDate, int dayCount, boolean dayOverridden, int eveningCount, boolean eveningOverridden` | `RequiredStaffService` |
| `ShiftWarning` | `WarningType type, LocalDate workDate, Byte slotId, String messageCode, Object[] args` | `ShiftWarningService` |
| `MatrixCell` | `RequestMark request, String requestTitle, AssignMark assign, Long assignmentId, String confirmCode, boolean disabled` | `AssignmentService` |
| `MatrixRow` | `Long userId, String name, boolean trainee, boolean submitted, Map<LocalDate, Map<Byte, MatrixCell>> cells` | `AssignmentService` |
| `CountCell` | `int assigned, int required, boolean shortage, boolean traineeOnly` | `AssignmentService` |
| `AssignmentMatrix` | `List<LocalDate> days, List<Slot> slots, List<MatrixRow> rows, Map<LocalDate, Map<Byte, CountCell>> counts` | `AssignmentService` |
| `TimeRequestRow` | `Long userId, String name, LocalDate workDate, LocalTime startTime, LocalTime endTime, boolean assigned` | `AssignmentService` |
| `TimeAssignmentRow` | `Long assignmentId, String name, LocalDate workDate, LocalTime startTime, LocalTime endTime, AssignmentStatus status` | `AssignmentService` |
| `PeriodPageView` | `PeriodOption period, LocalDateTime publishedAt, List<PeriodOption> periodOptions, AssignmentMatrix matrix, List<ShiftWarning> warnings, List<TimeRequestRow> timeRequests, List<TimeAssignmentRow> timeAssignments, boolean editable, boolean publishable, String notPublishableCode` | `AssignmentService` |
| `PublishResult` | `int assignmentCount, boolean hasWarnings` | `AssignmentService` |
| `AbsenceTargetRow` | `Long assignmentId, String slotLabel, String staffName, LocalDateTime registeredAt, LocalDateTime notifiedAt` | `AbsenceService` |
| `AbsenceDayView` | `LocalDate date, List<AbsenceTargetRow> candidatesOfDay, List<AbsenceTargetRow> absentsOfDay` | `AbsenceService` |
| `VacancyRecipientRow` | `Long userId, String name, boolean trainee` | `AbsenceService` |
| `AbsenceConfirmView` | `Long assignmentId, LocalDate workDate, String targetLabel, List<VacancyRecipientRow> recipients, String defaultMessage` | `AbsenceService` |
| `ManagerRow` | `Long id, String name, String loginId, boolean active, boolean locked, LocalDateTime createdAt, boolean lastActive` | `ManagerAccountService` |
| `ManagerCountView` | `long active, long stopped` | `ManagerAccountService` |
| `OperationLogRow` | `Long id, LocalDateTime createdAt, String userName, String actionLabel, String target, String detail, String ipAddress` | `OperationLogService` |
| `BackupRow` | `Long id, LocalDateTime createdAt, String fileName, Long fileSize, BackupStatus status, String createdByName` | `BackupService` |
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
