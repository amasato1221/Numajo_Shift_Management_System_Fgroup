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
 * StaffForm（SM-03：スタッフ登録・編集）の入力チェックのテスト。
 *
 * <table>
 * <caption>入力チェックの境界値</caption>
 * <tr><th>項目</th><th>入力</th><th>結果</th></tr>
 * <tr><td>氏名</td><td>空／50文字／51文字</td><td>E2201／OK／E2202</td></tr>
 * <tr><td>ふりがな</td><td>空／50文字／51文字</td><td>E2203／OK／E2204</td></tr>
 * <tr><td>ふりがな</td><td>ひらがな・長音・空白／カタカナ／漢字</td><td>OK／E2205／E2205</td></tr>
 * <tr><td>ログインID</td><td>空／3文字／4文字／20文字／21文字／記号入り</td>
 * <td>E2206／E2207／OK／OK／E2207／E2207</td></tr>
 * </table>
 */
class StaffFormTests {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static StaffForm valid() {
        StaffForm form = new StaffForm();
        form.setName("山田 太郎");
        form.setNameKana("やまだ たろう");
        form.setLoginId("yamada");
        return form;
    }

    /** エラーになった項目のメッセージコード（"{E2201}" の E2201）を集める。 */
    private static Set<String> codes(StaffForm form) {
        return validator.validate(form).stream()
                .map(ConstraintViolation::getMessageTemplate)
                .map(template -> template.substring(1, template.length() - 1))
                .collect(Collectors.toSet());
    }

    @Test
    void 正しい入力ならエラーなし() {
        assertThat(codes(valid())).isEmpty();
    }

    @Test
    void 初期値は有効で研修中ではなく職能なし() {
        StaffForm form = new StaffForm();

        assertThat(form.isActive()).isTrue();
        assertThat(form.isTrainee()).isFalse();
        assertThat(form.getSkillIds()).isEmpty();
    }

    @Test
    void 全項目が空ならE2201_E2203_E2206() {
        StaffForm form = new StaffForm();
        form.setName("");
        form.setNameKana("");
        form.setLoginId("");

        assertThat(codes(form)).contains("E2201", "E2203", "E2206");
    }

    @Test
    void 氏名は50文字までOKで51文字はE2202() {
        StaffForm ok = valid();
        ok.setName("あ".repeat(50));
        StaffForm ng = valid();
        ng.setName("あ".repeat(51));

        assertThat(codes(ok)).isEmpty();
        assertThat(codes(ng)).containsExactly("E2202");
    }

    @Test
    void ふりがなは50文字までOKで51文字はE2204() {
        StaffForm ok = valid();
        ok.setNameKana("あ".repeat(50));
        StaffForm ng = valid();
        ng.setNameKana("あ".repeat(51));

        assertThat(codes(ok)).isEmpty();
        assertThat(codes(ng)).containsExactly("E2204");
    }

    @Test
    void ふりがなはひらがなと長音と空白だけOK() {
        StaffForm half = valid();
        half.setNameKana("らーめん たろう");
        StaffForm full = valid();
        full.setNameKana("らーめん　たろう");

        assertThat(codes(half)).isEmpty();
        assertThat(codes(full)).isEmpty();
    }

    @Test
    void ふりがなにカタカナや漢字があるとE2205() {
        StaffForm katakana = valid();
        katakana.setNameKana("ヤマダ");
        StaffForm kanji = valid();
        kanji.setNameKana("山田");

        assertThat(codes(katakana)).containsExactly("E2205");
        assertThat(codes(kanji)).containsExactly("E2205");
    }

    @Test
    void ログインIDは4文字から20文字までOK() {
        StaffForm four = valid();
        four.setLoginId("abcd");
        StaffForm twenty = valid();
        twenty.setLoginId("a".repeat(20));

        assertThat(codes(four)).isEmpty();
        assertThat(codes(twenty)).isEmpty();
    }

    @Test
    void ログインIDが3文字か21文字ならE2207() {
        StaffForm three = valid();
        three.setLoginId("abc");
        StaffForm twentyOne = valid();
        twentyOne.setLoginId("a".repeat(21));

        assertThat(codes(three)).containsExactly("E2207");
        assertThat(codes(twentyOne)).containsExactly("E2207");
    }

    @Test
    void ログインIDに記号や全角があるとE2207() {
        StaffForm symbol = valid();
        symbol.setLoginId("abc-def");
        StaffForm wide = valid();
        wide.setLoginId("ａｂｃｄ");

        assertThat(codes(symbol)).containsExactly("E2207");
        assertThat(codes(wide)).containsExactly("E2207");
    }
}
