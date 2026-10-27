package jp.numajo.fgroup.shift.constant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * constant パッケージの列挙型のテスト（97_テスト計画書 4.1）。
 *
 * <p>
 * 判定のあるコードは StaffStatusFilter の parse と toActiveParam だけ。
 * ほかの列挙型は、画面に出す日本語名・CSSクラスが設計書どおりかを確かめる。
 * </p>
 *
 * <table>
 * <caption>StaffStatusFilter#parse の条件の組み合わせ</caption>
 * <tr><th>value が null</th><th>名前が一致</th><th>結果</th></tr>
 * <tr><td>はい</td><td>-</td><td>ACTIVE</td></tr>
 * <tr><td>いいえ</td><td>はい</td><td>その値</td></tr>
 * <tr><td>いいえ</td><td>いいえ</td><td>ACTIVE</td></tr>
 * </table>
 */
class ConstantTests {

    @Test
    void parse_ALLを渡すとALL() {
        StaffStatusFilter actual = StaffStatusFilter.parse("ALL");

        assertThat(actual).isEqualTo(StaffStatusFilter.ALL);
    }

    @Test
    void parse_ACTIVEを渡すとACTIVE() {
        StaffStatusFilter actual = StaffStatusFilter.parse("ACTIVE");

        assertThat(actual).isEqualTo(StaffStatusFilter.ACTIVE);
    }

    @Test
    void parse_INACTIVEを渡すとINACTIVE() {
        StaffStatusFilter actual = StaffStatusFilter.parse("INACTIVE");

        assertThat(actual).isEqualTo(StaffStatusFilter.INACTIVE);
    }

    @Test
    void parse_nullを渡すとACTIVE() {
        StaffStatusFilter actual = StaffStatusFilter.parse(null);

        assertThat(actual).isEqualTo(StaffStatusFilter.ACTIVE);
    }

    @Test
    void parse_知らない文字列を渡すとACTIVE() {
        StaffStatusFilter actual = StaffStatusFilter.parse("UNKNOWN");

        assertThat(actual).isEqualTo(StaffStatusFilter.ACTIVE);
    }

    @Test
    void parse_小文字や空文字は一致せずACTIVE() {
        assertThat(StaffStatusFilter.parse("all")).isEqualTo(StaffStatusFilter.ACTIVE);
        assertThat(StaffStatusFilter.parse("")).isEqualTo(StaffStatusFilter.ACTIVE);
    }

    @Test
    void toActiveParam_ALLはnull() {
        Boolean actual = StaffStatusFilter.ALL.toActiveParam();

        assertThat(actual).isNull();
    }

    @Test
    void toActiveParam_ACTIVEはtrue() {
        Boolean actual = StaffStatusFilter.ACTIVE.toActiveParam();

        assertThat(actual).isTrue();
    }

    @Test
    void toActiveParam_INACTIVEはfalse() {
        Boolean actual = StaffStatusFilter.INACTIVE.toActiveParam();

        assertThat(actual).isFalse();
    }

    @Test
    void SlotIds_日勤は1で夕勤は2() {
        assertThat(SlotIds.DAY).isEqualTo((byte) 1);
        assertThat(SlotIds.EVENING).isEqualTo((byte) 2);
    }

    @Test
    void SlotIds_privateコンストラクタを呼んでもインスタンスができる() throws Exception {
        Constructor<SlotIds> constructor = SlotIds.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        Object actual = constructor.newInstance();

        assertThat(actual).isNotNull();
    }

    @Test
    void BadgeType_CSSクラスが20_00の4章どおり() {
        assertThat(BadgeType.DAY.getCssClass()).isEqualTo("badge-day");
        assertThat(BadgeType.EVENING.getCssClass()).isEqualTo("badge-evening");
        assertThat(BadgeType.TIME.getCssClass()).isEqualTo("badge-time");
        assertThat(BadgeType.PENDING.getCssClass()).isEqualTo("badge-pending");
        assertThat(BadgeType.ABSENT.getCssClass()).isEqualTo("badge-absent");
        assertThat(BadgeType.values()).hasSize(5);
    }

    @Test
    void PeriodStatus_日本語名が10の5_7どおり() {
        assertThat(PeriodStatus.OPEN.getLabel()).isEqualTo("受付中");
        assertThat(PeriodStatus.CLOSED.getLabel()).isEqualTo("作成中");
        assertThat(PeriodStatus.PUBLISHED.getLabel()).isEqualTo("公開済み");
        assertThat(PeriodStatus.values()).hasSize(3);
    }

    @Test
    void NotificationType_日本語名が設計書どおり() {
        assertThat(NotificationType.DEADLINE.getLabel()).isEqualTo("締切");
        assertThat(NotificationType.PUBLISHED.getLabel()).isEqualTo("公開");
        assertThat(NotificationType.CHANGED.getLabel()).isEqualTo("変更");
        assertThat(NotificationType.VACANCY.getLabel()).isEqualTo("欠員");
        assertThat(NotificationType.CHAT.getLabel()).isEqualTo("チャット");
        assertThat(NotificationType.values()).hasSize(5);
    }

    @Test
    void SubmissionState_日本語名が設計書どおり() {
        assertThat(SubmissionState.NOT_SUBMITTED.getLabel()).isEqualTo("未提出");
        assertThat(SubmissionState.SUBMITTED.getLabel()).isEqualTo("提出済み");
        assertThat(SubmissionState.CLOSED.getLabel()).isEqualTo("受付終了");
        assertThat(SubmissionState.values()).hasSize(3);
    }

    @Test
    void OperationAction_24種類の日本語名が20_SA_03の2_3どおり() {
        List<String> actual = Arrays.stream(OperationAction.values()).map(OperationAction::getLabel).toList();

        assertThat(actual).containsExactly(
                "ログイン", "ログイン失敗", "アカウントのロック", "ログアウト", "パスワード変更",
                "スタッフの登録", "スタッフの変更", "スタッフのパスワード初期化",
                "店長アカウントの作成", "店長アカウントの停止", "店長アカウントの再開", "店長のパスワード初期化",
                "職能の追加", "職能の変更", "職能の削除", "枠の必要職能の変更",
                "必要人数の変更", "希望の代理修正", "割当の追加", "割当の削除",
                "シフトの公開", "欠勤登録", "店舗設定の変更", "バックアップ作成");
    }

    @Test
    void 名前だけの列挙型_値の名前と順番が設計どおり() {
        assertThat(names(AssignmentStatus.values())).containsExactly("ASSIGNED", "ABSENT");
        assertThat(names(Availability.values())).containsExactly("OK", "NG", "TIME");
        assertThat(names(BackupStatus.values())).containsExactly("SUCCESS", "FAILED");
        assertThat(names(RequestMode.values())).containsExactly("SLOT", "TIME");
        assertThat(names(Role.values())).containsExactly("ADMIN", "MANAGER", "STAFF");
        assertThat(names(WarningType.values()))
                .containsExactly("TRAINEE_ONLY", "SHORTAGE", "SKILL", "INACTIVE_STAFF");
    }

    private List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
