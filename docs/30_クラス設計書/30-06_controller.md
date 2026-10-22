# 30-06 controller

| 項目 | 内容 |
| --- | --- |
| 文書番号 | 30-06 |
| 版 | 第1.1版（令和8年10月22日） |
| 対象読者 | 実装をする班員 |

## 改版履歴

| 版 | 日付 | 内容 | 作成者 |
| --- | --- | --- | --- |
| 1.0 | 令和8年10月21日 | 初版作成 | 新井 |
| 1.1 | 令和8年10月22日 | 設計書評価の指摘を反映：StringTrimmerEditor は SC-01 には効かないことを明記（L6） | 新井 |

## 0. controller の決まり

| 決まり | 内容 |
| --- | --- |
| 付けるアノテーション | `@Controller`、`@RequiredArgsConstructor`。URLの共通部分はクラスに `@RequestMapping("/manager/staff")` のように付ける |
| 戻り値 | テンプレート名（`"manager/staff-list"`）か `"redirect:/…"`。IC-01 だけ `ResponseEntity<String>` |
| ログイン中の人 | 引数に `@AuthenticationPrincipal LoginUser loginUser` |
| フォーム | `@Validated @ModelAttribute("xxxForm") XxxForm form, BindingResult bindingResult` の順に必ず並べる |
| 業務エラー | 20-00 2.2 の形で `BusinessException` を受け取り、フラッシュ属性に入れてリダイレクト |
| 404 | `ResourceNotFoundException` は受け取らない（`GlobalExceptionHandler` に任せる） |
| 数値のパラメーター | `@PathVariable Long id`。数値でない値が来たら Spring が 400 にする（`GlobalExceptionHandler` で 404 画面にする） |

表の「引数」では、`LoginUser`・`Model`・`RedirectAttributes`・`BindingResult` を省略せずに書いています。順番もこのとおりにします。

## 1. controller.common

### 1.1 `RootController`

| メソッド | マッピング | 引数 | 戻り値 |
| --- | --- | --- | --- |
| `root` | GET `/` | `LoginUser loginUser`（null 可） | 未ログイン → `redirect:/login`、ログイン済み → `redirect:` ＋ `HomeUrl.of(role)` |

### 1.2 `LoginController`

| メソッド | マッピング | 引数 | 戻り値 |
| --- | --- | --- | --- |
| `login` | GET `/login` | `Authentication authentication` | 20-SC-01 2.1 |

### 1.3 `NoticeController`（`@RequestMapping("/notices")`）

| メソッド | マッピング | 引数 | 戻り値 |
| --- | --- | --- | --- |
| `list` | GET `` | `LoginUser, Model` | `common/notices` |
| `open` | POST `/{id}/open` | `Long id, LoginUser` | `redirect:` ＋ リンク先 |
| `readAll` | POST `/read-all` | `LoginUser, RedirectAttributes` | `redirect:/notices` |

### 1.4 `AccountController`（`@RequestMapping("/account/password")`）

| メソッド | マッピング | 引数 | 戻り値 |
| --- | --- | --- | --- |
| `show` | GET `` | `Model` | `common/account-password` |
| `change` | POST `` | `PasswordChangeForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SC-03 2.2 |

### 1.5 `CalendarFeedController`

| メソッド | マッピング | 引数 | 戻り値 |
| --- | --- | --- | --- |
| `feed` | GET `/ical/{token}.ics` | `String token` | `ResponseEntity<String>`（20-IC-01 2-3）。マッピングは `@GetMapping("/ical/{token:[A-Za-z0-9_-]{43}}.ics")` と書き、形が違うURLは Spring が 404 にする |

### 1.6 `GlobalModelAdvice`（`@ControllerAdvice`）

```java
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final StoreSettingService storeSettingService;
    private final NotificationService notificationService;

    /** 前後の空白を取り、空文字を null にする（20-00 5章）。 */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    @ModelAttribute("storeName")
    public String storeName() {
        return storeSettingService.get().getStoreName();
    }

    @ModelAttribute("showOthersShifts")
    public boolean showOthersShifts() {
        return storeSettingService.get().isShowOthersShifts();
    }

    @ModelAttribute("unreadCount")
    public long unreadCount(@AuthenticationPrincipal LoginUser loginUser) {
        return loginUser == null ? 0 : notificationService.countUnread(loginUser.getUserId());
    }

    @ModelAttribute("loginUser")
    public LoginUser loginUser(@AuthenticationPrincipal LoginUser loginUser) {
        return loginUser;
    }
}
```

- `StringTrimmerEditor` は、データバインド（`@ModelAttribute` のフォーム）を通る `String` 型の入力に効きます。SC-03 のパスワードは前後に空白があっても取ってしまうため、パスワードに空白を使えないことを画面に書いています（SC-03 の E0303 の正規表現でも空白は使えない）。
- SC-01（ログイン）の入力は Spring Security のフィルターが直接読むため、データバインドを通らず、`StringTrimmerEditor` は効きません（空白はそのまま比べられ、空白を含むパスワードは登録できないので一致しない）。
- `CalendarFeedController`（IC-01）にも動きますが、IC-01 はテンプレートを使わないため影響しません。

## 2. controller.staff

| クラス（`@RequestMapping`） | メソッド | マッピング | 引数 | 詳細設計 |
| --- | --- | --- | --- | --- |
| `StaffHomeController`（`/staff/home`） | `home` | GET | `String ym, LoginUser, Model` | 20-SB-01 |
| `StaffShiftController`（`/staff`） | `shifts` | GET `/shifts` | `Long periodId, LoginUser, Model` | 20-SB-02 |
| 〃 | `allShifts` | GET `/all-shifts` | `Long periodId, LoginUser, Model, RedirectAttributes` | 20-SB-04 |
| `StaffRequestController`（`/staff/requests`） | `show` | GET | `Long periodId, LoginUser, Model` | 20-SB-03 3.1 |
| 〃 | `submit` | POST | `ShiftRequestForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SB-03 3.2 |
| 〃 | `cancel` | POST `/cancel` | `Long periodId, LoginUser, RedirectAttributes` | 20-SB-03 3.3 |
| `StaffSettingController`（`/staff/settings`） | `show` | GET | `LoginUser, Model` | 20-SB-05 2.1 |
| 〃 | `saveNotifications` | POST `/notifications` | `NotificationSettingForm, LoginUser, RedirectAttributes` | 20-SB-05 2.2 |
| 〃 | `reissue` | POST `/calendar/reissue` | `LoginUser, RedirectAttributes` | 20-SB-05 2.3 |
| `StaffChatController`（`/staff/chat`） | `show` | GET | `LoginUser, Model` | 20-SB-06 2.1 |
| 〃 | `send` | POST | `ChatForm, BindingResult, LoginUser, Model` | 20-SB-06 2.2 |

`String ym`・`Long periodId` は `@RequestParam(required = false)`。`ym` は文字列で受け、Controller の中で `YearMonth.parse` し、失敗したら今月にします（`DateTimeParseException` を catch）。

## 3. controller.manager

| クラス（`@RequestMapping`） | メソッド | マッピング | 引数 | 詳細設計 |
| --- | --- | --- | --- | --- |
| `ManagerHomeController`（`/manager/home`） | `home` | GET | `Model` | 20-SM-01 |
| `ManagerStaffController`（`/manager/staff`） | `list` | GET | `String keyword, String status, Model` | 20-SM-02 |
| 〃 | `newForm` | GET `/new` | `Model` | 20-SM-03 2.1 |
| 〃 | `create` | POST | `StaffForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-03 2.2 |
| 〃 | `edit` | GET `/{id}` | `Long id, Model` | 20-SM-03 2.3 |
| 〃 | `update` | POST `/{id}` | `Long id, StaffForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-03 2.4 |
| 〃 | `resetPassword` | POST `/{id}/reset-password` | `Long id, LoginUser, RedirectAttributes` | 20-SM-03 2.5 |
| `ManagerSkillController`（`/manager/skills`） | `show` | GET | `Model` | 20-SM-04 2.1 |
| 〃 | `create` | POST | `SkillForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-04 2.2 |
| 〃 | `update` | POST `/{id}` | `Long id, SkillForm, BindingResult, LoginUser, RedirectAttributes` | 20-SM-04 2.3 |
| 〃 | `delete` | POST `/{id}/delete` | `Long id, LoginUser, RedirectAttributes` | 20-SM-04 2.4 |
| 〃 | `saveSlotRequired` | POST `/slot-required` | `SlotSkillForm, LoginUser, RedirectAttributes` | 20-SM-04 2.5 |
| `ManagerRequiredStaffController`（`/manager/required`） | `show` | GET | `String ym, Model` | 20-SM-05 2.1 |
| 〃 | `saveDefaults` | POST `/defaults` | `RequiredDefaultForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-05 2.2 |
| 〃 | `saveOverride` | POST `/overrides` | `RequiredOverrideForm, BindingResult, String ym, LoginUser, RedirectAttributes` | 20-SM-05 2.3 |
| 〃 | `deleteOverride` | POST `/overrides/delete` | `LocalDate workDate, Byte slotId, String ym, LoginUser, RedirectAttributes` | 20-SM-05 2.4 |
| `ManagerPeriodController`（`/manager/periods`） | `redirectDefault` | GET | `RedirectAttributes` | 20-SM-06 3.1 |
| 〃 | `show` | GET `/{id}` | `Long id, Model` | 20-SM-06 3.2 |
| 〃 | `toggle` | POST `/{id}/assignments/toggle` | `Long id, AssignmentToggleForm, LoginUser, RedirectAttributes` | 20-SM-06 3.3 |
| 〃 | `assignTime` | POST `/{id}/assignments/time` | `Long id, TimeAssignmentForm, BindingResult, LoginUser, RedirectAttributes` | 20-SM-06 3.4 |
| 〃 | `deleteAssignment` | POST `/{id}/assignments/{assignmentId}/delete` | `Long id, Long assignmentId, LoginUser, RedirectAttributes` | 20-SM-06 3.5 |
| 〃 | `publish` | POST `/{id}/publish` | `Long id, LoginUser, RedirectAttributes` | 20-SM-06 3.6 |
| 〃 | `proxyForm` | GET `/{id}/requests/{userId}` | `Long id, Long userId, Model, RedirectAttributes` | 20-SM-06-1 3.1 |
| 〃 | `proxySubmit` | POST `/{id}/requests/{userId}` | `Long id, Long userId, ShiftRequestForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-06-1 3.2 |
| 〃 | `print` | GET `/{id}/print` | `Long id, Model` | 20-SM-08 3.1 |
| `ManagerAbsenceController`（`/manager/absences`） | `show` | GET | `String date, Long assignmentId, Model, RedirectAttributes` | 20-SM-07 3.1・3.2 |
| 〃 | `register` | POST | `AbsenceForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-07 3.3 |
| `ManagerSettingController`（`/manager/settings`） | `show` | GET | `Model` | 20-SM-09 2.1 |
| 〃 | `save` | POST | `StoreSettingForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-09 2.2 |
| `ManagerChatController`（`/manager/chats`） | `list` | GET | `Model` | 20-SM-10 3.1 |
| 〃 | `show` | GET `/{staffId}` | `Long staffId, LoginUser, Model` | 20-SM-11 2.1 |
| 〃 | `send` | POST `/{staffId}` | `Long staffId, ChatForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SM-11 2.2 |

`LocalDate workDate` は `@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`。

## 4. controller.admin

| クラス（`@RequestMapping`） | メソッド | マッピング | 引数 | 詳細設計 |
| --- | --- | --- | --- | --- |
| `AdminHomeController`（`/admin/home`） | `home` | GET | `Model` | 20-SA-01 |
| `AdminManagerController`（`/admin/managers`） | `list` | GET | `Model` | 20-SA-02 2.1 |
| 〃 | `create` | POST | `ManagerForm, BindingResult, LoginUser, Model, RedirectAttributes` | 20-SA-02 2.2 |
| 〃 | `stop` | POST `/{id}/stop` | `Long id, LoginUser, RedirectAttributes` | 20-SA-02 2.3 |
| 〃 | `resume` | POST `/{id}/resume` | `Long id, LoginUser, RedirectAttributes` | 20-SA-02 2.4 |
| 〃 | `resetPassword` | POST `/{id}/reset-password` | `Long id, LoginUser, RedirectAttributes` | 20-SA-02 2.5 |
| `AdminLogController`（`/admin/logs`） | `list` | GET | `LogSearchForm, BindingResult, Model`（`@Validated` は付けない。型の合わない値で出た `BindingResult` のエラーは無視する） | 20-SA-03 2.1 |
| `AdminBackupController`（`/admin/backups`） | `list` | GET | `Model` | 20-SA-04 2.1 |
| 〃 | `create` | POST | `LoginUser, RedirectAttributes` | 20-SA-04 2.2 |

## 5. エラー画面（exception.GlobalExceptionHandler）

`@ControllerAdvice` で次の例外を受け取ります（30-08 2章）。

| 例外 | ステータス | 画面 |
| --- | --- | --- |
| `ResourceNotFoundException`、`NoResourceFoundException`、`MethodArgumentTypeMismatchException` | 404 | `error/404` |
| `Exception`（上以外） | 500 | `error/500`（`log.error` でスタックトレースを出す） |
