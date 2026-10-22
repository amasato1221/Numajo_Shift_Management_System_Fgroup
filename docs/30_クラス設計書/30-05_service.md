# 30-05 service

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-05 |
| 版 | 第1.0版（令和8年10月21日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |

## 0. service の決まり

| 決まり | 内容 |
| --- | --- |
| 付けるアノテーション | クラスに `@Service`、`@RequiredArgsConstructor`、`@Transactional(readOnly = true)`。更新するメソッドに `@Transactional` |
| 受け取る値 | ID・値・form（30-00 4章の例外だけ）。`HttpServletRequest`・`Model`・`LoginUser` は受け取らない（ユーザーIDの `Long` で受け取る） |
| 返す値 | dto の record、entity（表示だけに使うとき）、`void` |
| エラー | 業務エラーは `BusinessException(メッセージコード, 引数…)`、対象がない・権限がない（自分のデータでない）は `ResourceNotFoundException` |
| 操作ログ | 94_セキュリティ設計書 4章の操作は、業務処理の最後に `operationLogService.record(...)` を呼ぶ（同じトランザクション） |
| お知らせ | 作るのは `NotificationService` だけ。ほかの Service はそのメソッドを呼ぶ |
| 日時 | `Clock clock` を受け取り、`LocalDateTime.now(clock)` |
| メッセージの文 | Service の中で文を組み立てるとき（お知らせの本文など）は `MessageSource#getMessage(コード, 引数, Locale.JAPAN)` を使い、日本語を直接書かない |

各メソッドの手順は「手順」の列の詳細設計書の章に書いてあります。ここには、引数・戻り値・トランザクション・主な依存だけを書きます。

## 1. 共通（SC・IC）

### 1.1 `UserService`

依存：`UserRepository`、`PasswordEncoder`、`OperationLogService`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `void changePassword(Long userId, String currentPassword, String newPassword, String confirmPassword)` | 更新 | 20-SC-03 2.2 |

### 1.2 `NotificationService`

依存：`NotificationRepository`、`UserRepository`、`MessageSource`、`Clock`

| メソッド | Tx | 内容・手順 |
| --- | --- | --- |
| `List<NoticeRow> findLatest(Long userId, int limit)` | 読取 | 20-SC-02 2.1（limit は 100 だけを使う。`findTop100…` を呼ぶ） |
| `List<NoticeRow> findUnreadTop3(Long userId)` | 読取 | 20-SB-01 2.1-3 |
| `String open(Long userId, Long notificationId)` | 更新 | 20-SC-02 2.2。戻り値はリダイレクト先 |
| `void readAll(Long userId)` | 更新 | 20-SC-02 2.3 |
| `long countUnread(Long userId)` | 読取 | ヘッダーの未読数 |
| `void notifyPublished(ShiftPeriod period)` | 必須 | 有効なバイト全員へ PUBLISHED（20-SM-06 3.6） |
| `void notifyChanged(User staff, ShiftPeriod period, String body)` | 必須 | 本人へ CHANGED（件名 N0003） |
| `void notifyVacancy(List<User> recipients, ShiftAssignment assignment, String body)` | 必須 | 相手へ VACANCY（件名 N0004） |
| `void notifyChat(Long staffUserId, User sender, String body)` | 必須 | 送ったのがバイトなら有効な店長全員へ、店長ならそのバイトへ CHAT（件名 N0005） |
| `int sendDeadlineReminders(LocalDate today)` | 更新 | BT-03（95_通知・定期処理設計書 4章）。戻り値は作った件数 |
| `private void create(User to, NotificationType type, String title, String body, String linkUrl)` | － | 1件作る。`title` は100文字、`body` は500文字で切る |

「必須」は `@Transactional(propagation = Propagation.MANDATORY)`：呼び出し元の業務処理と同じトランザクションの中でだけ動く（お知らせだけ残って業務処理が失敗する、を防ぐ）。

### 1.3 `NotificationSettingService`

依存：`NotificationSettingRepository`、`UserRepository`

| メソッド・定数 | Tx | 内容・手順 |
| --- | --- | --- |
| `static final List<Integer> DAYS_OPTIONS = List.of(0, 1, 3, 7)` | － | 選択肢 |
| `static final List<Integer> DEFAULT_DAYS = List.of(1, 3)` | － | 既定値 |
| `List<Integer> findDays(Long userId)` | 読取 | 20-SB-05 2.1 |
| `void save(Long userId, List<Integer> daysBefore)` | 更新 | 20-SB-05 2.2 |
| `void createDefault(Long userId)` | 必須 | 20-SM-03 2.2-6 |

### 1.4 `CalendarFeedService`

依存：`CalendarFeedRepository`、`UserRepository`、`ShiftAssignmentRepository`、`StoreSettingService`、`TokenGenerator`、`AppProperties`、`Clock`

| メソッド | Tx | 内容・手順 |
| --- | --- | --- |
| `CalendarFeed getOrCreate(Long userId)` | 更新 | 20-SB-05 2.1-2（GET の画面表示でも行を作ることがあるため、読取専用にしない） |
| `CalendarFeed reissue(Long userId)` | 更新 | 20-SB-05 2.3 |
| `String feedUrl(String token)` | － | `appProperties.getBaseUrl() + "/ical/" + token + ".ics"` |
| `String webcalUrl(String token)` | － | `feedUrl` の先頭の `http://`・`https://` を `webcal://` に |
| `String buildIcs(String token)` | 読取 | 20-IC-01 2〜3 |

## 2. バイト（SB）

### 2.1 `StaffHomeService`

依存：`ShiftAssignmentRepository`、`ShiftPeriodRepository`、`NotificationService`、`ShiftCalendarService`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `StaffHomeView getHome(Long userId, YearMonth ym)` | 読取 | 20-SB-01 2.1 |

### 2.2 `ShiftCalendarService`

依存：`ShiftAssignmentRepository`、`ShiftRequestRepository`、`ShiftPeriodRepository`、`SlotRepository`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `MonthCalendar buildMonth(Long userId, YearMonth ym)` | 読取 | 20-SB-01 2.2 |
| `PeriodCalendar buildPeriod(Long userId, ShiftPeriod period)` | 読取 | 20-SB-02 2.1-3 |
| `Map<LocalDate, List<ShiftBadge>> buildBadges(Long userId, LocalDate from, LocalDate to)` | 読取 | 20-SB-01 2.3 |
| `static ShiftBadge toBadge(ShiftAssignment a, List<Slot> slots)` | － | 割当→バッジ（DAY・EVENING・TIME・ABSENT） |
| `static ShiftBadge toPendingBadge(ShiftRequest r, List<Slot> slots)` | － | 希望→未確定バッジ（OK は枠の時刻、TIME は希望の時刻） |

### 2.3 `ShiftTableService`

依存：`ShiftAssignmentRepository`、`UserRepository`、`SlotRepository`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `ShiftTable build(ShiftPeriod period, boolean includeAbsent)` | 読取 | 20-SB-04 2.1-4、20-SM-08 3.1 |
| `Map<LocalDate, Map<Byte, Integer>> slotCounts(ShiftTable table)` | － | 20-SM-08 3.1-3（ASSIGNED だけを、重なる枠ごとに数える） |

### 2.4 `ShiftRequestService`

依存：`ShiftPeriodRepository`、`ShiftRequestRepository`、`RequestSubmissionRepository`、`UserRepository`、`SlotRepository`、`PeriodService`、`OperationLogService`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `RequestPageView getRequestPage(Long userId, Long periodId)` | 読取 | 20-SB-03 3.1。表示する期間がないときは `Optional.empty()` ではなく `null` を返す（テンプレートで `page == null` を見るため） |
| `ShiftRequestForm toForm(Long userId, ShiftPeriod period)` | 読取 | 20-SB-03 3.1-5 |
| `void submit(Long userId, ShiftRequestForm form)` | 更新 | 20-SB-03 3.2 |
| `void cancel(Long userId, Long periodId)` | 更新 | 20-SB-03 3.3 |
| `RequestPageView getProxyPage(Long periodId, Long staffUserId)` | 読取 | 20-SM-06-1 3.1 |
| `void proxySubmit(Long periodId, Long staffUserId, ShiftRequestForm form, Long operatorId)` | 更新 | 20-SM-06-1 3.2 |
| `Set<Byte> qualifiedSlotIds(Long userId)` | 読取 | 20-SB-03 3.1-1-3 |
| `void validateDays(ShiftPeriod period, List<DayRequestForm> days, Set<Byte> qualified)` | － | 20-SB-03 3.2-2-3〜2-4 |
| `void replaceRequests(ShiftPeriod period, Long userId, List<DayRequestForm> days)` | 必須 | 20-SB-03 3.2-3（package-private） |

### 2.5 `ChatService`

依存：`ChatMessageRepository`、`UserRepository`、`NotificationService`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `List<ChatMessageView> getConversation(Long staffUserId, Long viewerUserId)` | 更新 | 20-SB-06 2.1、20-SM-11 2.1（既読を付けるため更新） |
| `void send(Long staffUserId, Long senderUserId, String body)` | 更新 | 20-SB-06 2.2、20-SM-11 2.2 |
| `User getStaffForChat(Long staffId)` | 読取 | 20-SM-11 2.1-1 |
| `List<ChatListRow> getChatList()` | 読取 | 20-SM-10 3.1 |

## 3. 店長（SM）

### 3.1 `DashboardService`

依存：`ShiftPeriodRepository`、`UserRepository`、`RequestSubmissionRepository`、`AbsenceRepository`、`ShiftWarningService`、`RequiredStaffService`、`SlotRepository`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `DashboardView getDashboard()` | 読取 | 20-SM-01 2.1 |

### 3.2 `StaffService`

依存：`UserRepository`、`SkillRepository`、`PasswordEncoder`、`PasswordGenerator`、`NotificationSettingService`、`CalendarFeedService`、`OperationLogService`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `List<StaffRowView> search(String keyword, StaffStatusFilter status)` | 読取 | 20-SM-02 2.1 |
| `StaffCreatedResult create(StaffForm form, Long operatorId)` | 更新 | 20-SM-03 2.2 |
| `StaffForm getForEdit(Long id)` | 読取 | 20-SM-03 2.3 |
| `StaffDetailView getDetail(Long id)` | 読取 | 20-SM-03 2.3 |
| `void update(Long id, StaffForm form, Long operatorId)` | 更新 | 20-SM-03 2.4 |
| `String resetPassword(Long id, Long operatorId)` | 更新 | 20-SM-03 2.5 |

### 3.3 `SkillService`

依存：`SkillRepository`、`SlotRepository`、`OperationLogService`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `List<Skill> findAll()` | 読取 | 名前順（`findAllByOrderByNameAscIdAsc`） |
| `List<SkillRowView> findRows()` | 読取 | 20-SM-04 2.1-1 |
| `SlotSkillForm getSlotSkillForm()` | 読取 | 20-SM-04 2.1-2 |
| `void create(String name, String description, Long operatorId)` | 更新 | 20-SM-04 2.2 |
| `void update(Long id, String name, String description, Long operatorId)` | 更新 | 20-SM-04 2.3 |
| `void delete(Long id, Long operatorId)` | 更新 | 20-SM-04 2.4 |
| `void saveSlotRequired(List<Long> daySkillIds, List<Long> eveningSkillIds, Long operatorId)` | 更新 | 20-SM-04 2.5 |

### 3.4 `RequiredStaffService`

依存：`RequiredStaffDefaultRepository`、`RequiredStaffOverrideRepository`、`OperationLogService`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `RequiredDefaultForm getDefaultForm()` | 読取 | 20-SM-05 2.1-2 |
| `List<RequiredDayView> getMonth(YearMonth ym)` | 読取 | 20-SM-05 2.1-3 |
| `void saveDefaults(List<Integer> dayCounts, List<Integer> eveningCounts, Long operatorId)` | 更新 | 20-SM-05 2.2 |
| `void saveOverride(LocalDate workDate, Byte slotId, int count, Long operatorId)` | 更新 | 20-SM-05 2.3 |
| `void deleteOverride(LocalDate workDate, Byte slotId, Long operatorId)` | 更新 | 20-SM-05 2.4 |
| `int requiredCount(LocalDate workDate, Byte slotId)` | 読取 | 20-SM-05 2.5 |
| `Map<LocalDate, Map<Byte, Integer>> requiredCounts(LocalDate from, LocalDate to)` | 読取 | 20-SM-05 2.5（SQLは2回） |
| `static byte dayOfWeek(LocalDate date)` | － | `date.getDayOfWeek().getValue() % 7`（日＝0） |

### 3.5 `PeriodService`

依存：`ShiftPeriodRepository`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `List<PeriodOption> findOptionsForStaff()` | 読取 | 20-SB-02 2.1-1 |
| `ShiftPeriod resolveDefault(List<PeriodOption> options, Long periodId)` | 読取 | 20-SB-02 2.1-2 |
| `boolean isAcceptingRequests(ShiftPeriod period)` | － | `status == OPEN && !now.isAfter(deadlineAt)` |
| `ShiftPeriod defaultPeriodForManager()` | 読取 | 20-SM-06 3.1 |
| `List<PeriodOption> findOptionsForManager()` | 読取 | 20-SM-06 2 の No.1 |
| `ShiftPeriod getPeriod(Long id)` | 読取 | なければ `ResourceNotFoundException` |
| `int createUpcomingPeriods(LocalDate today)` | 更新 | BT-01（95_通知・定期処理設計書 2章）。戻り値は作った数 |
| `int closeExpiredPeriods(LocalDateTime now)` | 更新 | BT-02（95_通知・定期処理設計書 3章）。戻り値は締めた数 |
| `static LocalDateTime deadlineOf(LocalDate startDate)` | － | 10_基本設計書 6.1 の期限の決め方 |
| `static PeriodOption toOption(ShiftPeriod p)` | － | 表示名「11/1〜11/15（期限 10/15）」を入れる |

### 3.6 `AssignmentService`

依存：`ShiftPeriodRepository`、`ShiftAssignmentRepository`、`ShiftRequestRepository`、`RequestSubmissionRepository`、`UserRepository`、`SlotRepository`、`RequiredStaffService`、`ShiftWarningService`、`NotificationService`、`OperationLogService`、`PeriodService`、`MessageSource`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `PeriodPageView getPeriodPage(Long periodId)` | 読取 | 20-SM-06 3.2 |
| `void toggleSlot(Long periodId, Long userId, LocalDate workDate, Byte slotId, Long operatorId)` | 更新 | 20-SM-06 3.3 |
| `void assignTime(Long periodId, Long userId, LocalDate workDate, LocalTime start, LocalTime end, Long operatorId)` | 更新 | 20-SM-06 3.4 |
| `void delete(Long periodId, Long assignmentId, Long operatorId)` | 更新 | 20-SM-06 3.5 |
| `PublishResult publish(Long periodId, Long operatorId)` | 更新 | 20-SM-06 3.6 |
| `private void checkNoOverlap(User user, LocalDate date, LocalTime start, LocalTime end)` | － | 重なる ASSIGNED があれば E2605 |

### 3.7 `ShiftWarningService`

依存：`ShiftAssignmentRepository`、`SlotRepository`、`RequiredStaffService`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `List<ShiftWarning> calculate(ShiftPeriod period)` | 読取 | 必要なデータを取ってから下のメソッドを呼ぶ |
| `List<ShiftWarning> calculate(ShiftPeriod period, List<Slot> slots, List<ShiftAssignment> assignments, Map<LocalDate, Map<Byte, Integer>> required)` | － | 20-SM-06 4章（DBを使わない。単体テストはこのメソッドで行う） |
| `int countFor(LocalDate date, Slot slot)` | 読取 | その日・その枠と重なる ASSIGNED の数（`findAssignedByWorkDate`） |

### 3.8 `AbsenceService`

依存：`ShiftAssignmentRepository`、`AbsenceRepository`、`UserRepository`、`SlotRepository`、`NotificationService`、`OperationLogService`、`MessageSource`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `AbsenceDayView getDay(LocalDate date)` | 読取 | 20-SM-07 3.1 |
| `AbsenceConfirmView getConfirm(Long assignmentId)` | 読取 | 20-SM-07 3.2 |
| `int register(Long assignmentId, String reason, String noticeMessage, Long operatorId)` | 更新 | 20-SM-07 3.3。戻り値は通知した人数 |
| `List<User> findVacancyRecipients(ShiftAssignment assignment)` | 読取 | 20-SM-07 3.4 |

### 3.9 `StoreSettingService`

依存：`StoreSettingRepository`、`OperationLogService`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `StoreSetting get()` | 読取 | 20-SM-09 2.1 |
| `void update(String storeName, boolean showOthersShifts, Long operatorId)` | 更新 | 20-SM-09 2.2 |

## 4. 管理者（SA）

### 4.1 `ManagerAccountService`

依存：`UserRepository`、`PasswordEncoder`、`PasswordGenerator`、`OperationLogService`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `List<ManagerRow> findAll()` | 読取 | 20-SA-02 2.1 |
| `ManagerCountView count()` | 読取 | 20-SA-01 2.1-1 |
| `String create(ManagerForm form, Long operatorId)` | 更新 | 20-SA-02 2.2。戻り値は初期パスワード |
| `void stop(Long id, Long operatorId)` | 更新 | 20-SA-02 2.3 |
| `void resume(Long id, Long operatorId)` | 更新 | 20-SA-02 2.4 |
| `String resetPassword(Long id, Long operatorId)` | 更新 | 20-SA-02 2.5 |

### 4.2 `OperationLogService`

全文は 30-04 の5章。次のメソッドを足します。依存：`OperationLogRepository`、`UserRepository`、`Clock`

| メソッド | Tx | 手順 |
| --- | --- | --- |
| `void record(Long userId, OperationAction action, String target, String detail)` | 必須 | 30-04 |
| `void recordInNewTransaction(Long userId, OperationAction action, String target, String detail)` | 新規 | 30-04 |
| `Page<OperationLogRow> search(LogSearchForm form, Pageable pageable)` | 読取 | 20-SA-03 2.1 |
| `List<OperationLogRow> findRecent(int limit)` | 読取 | 20-SA-01（limit は 5 だけを使う。`findLatest5` を呼ぶ） |

「新規」は `Propagation.REQUIRES_NEW`。

### 4.3 `BackupService`・`BackupRecorder`

依存（BackupService）：`BackupRepository`、`BackupRecorder`、`ProcessRunner`、`AppProperties`、`DataSourceProperties`、`Clock`

| クラス | メソッド | Tx | 手順 |
| --- | --- | --- | --- |
| `BackupService` | `List<BackupRow> findRecent(int limit)` | 読取 | 20-SA-04 2.1（limit は 50 だけ） |
| `BackupService` | `Optional<BackupRow> findLatest()` | 読取 | 20-SA-01 |
| `BackupService` | `BackupResult create(Long adminUserId)` | **なし**（`@Transactional(propagation = Propagation.NOT_SUPPORTED)`） | 20-SA-04 2.2 |
| `BackupRecorder` | `Backup record(String fileName, Long fileSize, BackupStatus status, Long adminUserId)` | 新規 | 20-SA-04 2.2-7（`backups` と操作ログを入れる） |

`BackupRecorder` を別クラスにするのは、同じクラスの中のメソッドを呼ぶと `@Transactional` が効かないためです。

## 5. 定期処理から使う Service

| 定期処理 | 呼ぶメソッド |
| --- | --- |
| BT-01 期間の作成 | `PeriodService#createUpcomingPeriods(LocalDate.now(clock))` |
| BT-02 締切 | `PeriodService#closeExpiredPeriods(LocalDateTime.now(clock))` |
| BT-03 締切のお知らせ | `NotificationService#sendDeadlineReminders(LocalDate.now(clock))` |

詳しくは 95_通知・定期処理設計書。
