package jp.numajo.fgroup.shift.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * ManagerForm（SA-02：店長アカウントの作成）の入力チェックのテスト。
 *
 * <table>
 * <caption>入力チェックの境界値</caption>
 * <tr><th>項目</th><th>入力</th><th>結果</th></tr>
 * <tr><td>氏名</td><td>null／空白だけ／50文字／51文字</td><td>E4201／E4201／OK／E4202</td></tr>
 * <tr><td>ふりがな</td><td>null／空／50文字／51文字</td><td>OK／OK／OK／E4203</td></tr>
 * <tr><td>ふりがな</td><td>ひらがな・長音・全角半角空白／カタカナ／漢字</td><td>OK／E4204／E4204</td></tr>
 * <tr><td>ログインID</td><td>null／空／3文字／4文字／20文字／21文字／記号入り</td>
 * <td>E4205／E4205・E4206／E4206／OK／OK／E4206／E4206</td></tr>
 * </table>
 */
class ManagerFormTests {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static ManagerForm valid() {
        ManagerForm form = new ManagerForm();
        form.setName("山田 店長");
        form.setNameKana("やまだ てんちょう");
        form.setLoginId("tencho");
        return form;
    }

    /** エラーになった項目のメッセージコード（"{E4201}" の E4201）を集める。 */
    private static Set<String> codes(ManagerForm form) {
        return validator.validate(form).stream()
                .map(ConstraintViolation::getMessageTemplate)
                .map(template -> template.substring(1, template.length() - 1))
                .collect(Collectors.toSet());
    }

    @Test
    void 正しい入力ならエラーなし() {
        assertThat(codes(valid())).isEmpty();
    }

    // ---------------------------------------------------------------- 氏名

    @Test
    void 氏名がnullならE4201() {
        ManagerForm form = valid();
        form.setName(null);
        assertThat(codes(form)).containsExactly("E4201");
    }

    @Test
    void 氏名が空白だけならE4201() {
        ManagerForm form = valid();
        form.setName("  ");
        assertThat(codes(form)).containsExactly("E4201");
    }

    @Test
    void 氏名が50文字ならOK() {
        ManagerForm form = valid();
        form.setName("あ".repeat(50));
        assertThat(codes(form)).isEmpty();
    }

    @Test
    void 氏名が51文字ならE4202() {
        ManagerForm form = valid();
        form.setName("あ".repeat(51));
        assertThat(codes(form)).containsExactly("E4202");
    }

    // ---------------------------------------------------------------- ふりがな（任意）

    @Test
    void ふりがながnullならOK() {
        ManagerForm form = valid();
        form.setNameKana(null);
        assertThat(codes(form)).isEmpty();
    }

    @Test
    void ふりがなが空ならOK() {
        ManagerForm form = valid();
        form.setNameKana("");
        assertThat(codes(form)).isEmpty();
    }

    @Test
    void ふりがなが50文字ならOK() {
        ManagerForm form = valid();
        form.setNameKana("あ".repeat(50));
        assertThat(codes(form)).isEmpty();
    }

    @Test
    void ふりがなが51文字ならE4203() {
        ManagerForm form = valid();
        form.setNameKana("あ".repeat(51));
        assertThat(codes(form)).containsExactly("E4203");
    }

    @Test
    void ふりがなはひらがな_長音_全角と半角の空白ならOK() {
        ManagerForm form = valid();
        form.setNameKana("ゆー てんちょう　さん");
        assertThat(codes(form)).isEmpty();
    }

    @Test
    void ふりがながカタカナならE4204() {
        ManagerForm form = valid();
        form.setNameKana("ヤマダ");
        assertThat(codes(form)).containsExactly("E4204");
    }

    @Test
    void ふりがなが漢字ならE4204() {
        ManagerForm form = valid();
        form.setNameKana("山田");
        assertThat(codes(form)).containsExactly("E4204");
    }

    @Test
    void ふりがなが51文字のカタカナならE4203とE4204の両方() {
        ManagerForm form = valid();
        form.setNameKana("ア".repeat(51));
        assertThat(codes(form)).containsExactlyInAnyOrder("E4203", "E4204");
    }

    // ---------------------------------------------------------------- ログインID

    @Test
    void ログインIDがnullならE4205() {
        ManagerForm form = valid();
        form.setLoginId(null);
        assertThat(codes(form)).containsExactly("E4205");
    }

    @Test
    void ログインIDが空ならE4205とE4206() {
        ManagerForm form = valid();
        form.setLoginId("");
        assertThat(codes(form)).containsExactlyInAnyOrder("E4205", "E4206");
    }

    @Test
    void ログインIDが3文字ならE4206() {
        ManagerForm form = valid();
        form.setLoginId("abc");
        assertThat(codes(form)).containsExactly("E4206");
    }

    @Test
    void ログインIDが4文字ならOK() {
        ManagerForm form = valid();
        form.setLoginId("Ab12");
        assertThat(codes(form)).isEmpty();
    }

    @Test
    void ログインIDが20文字ならOK() {
        ManagerForm form = valid();
        form.setLoginId("a".repeat(20));
        assertThat(codes(form)).isEmpty();
    }

    @Test
    void ログインIDが21文字ならE4206() {
        ManagerForm form = valid();
        form.setLoginId("a".repeat(21));
        assertThat(codes(form)).containsExactly("E4206");
    }

    @Test
    void ログインIDに記号が入るとE4206() {
        ManagerForm form = valid();
        form.setLoginId("ten_cho");
        assertThat(codes(form)).containsExactly("E4206");
    }

    @Test
    void 全部の項目が違反なら3項目ともエラー() {
        ManagerForm form = new ManagerForm();
        form.setName("");
        form.setNameKana("カナ");
        form.setLoginId("a!");
        assertThat(codes(form)).containsExactlyInAnyOrder("E4201", "E4204", "E4206");
    }
}
