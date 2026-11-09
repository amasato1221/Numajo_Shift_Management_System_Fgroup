package jp.numajo.fgroup.shift.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import jp.numajo.fgroup.shift.dto.SelectOption;

/**
 * util パッケージの単体テスト（30-08 0章、97_テスト計画書 3.2）。
 *
 * <pre>
 * 組み合わせ表1：ShiftTimeUtil#isOnGrid
 * 判定：A null でない && B 8:00 より前でない && C 22:00 より後でない && D 分が30の倍数 && E 秒が0 && F ナノ秒が0
 * No. | A | B | C | D | E | F | 結果 | テストの値
 *  1  | 真| 真| 真| 真| 真| 真| 真   | 8:00（下限ちょうど）、22:00（上限ちょうど）、13:30
 *  2  | 偽| － | － | － | － | － | 偽   | null
 *  3  | 真| 偽| － | － | － | － | 偽   | 7:30（下限の前）
 *  4  | 真| 真| 偽| － | － | － | 偽   | 22:30（上限の後）
 *  5  | 真| 真| 真| 偽| － | － | 偽   | 8:15（分が刻み外）
 *  6  | 真| 真| 真| 真| 偽| － | 偽   | 8:00:01
 *  7  | 真| 真| 真| 真| 真| 偽| 偽   | 8:00:00.000000001
 * 除外：すべて && でつないだ判定で、前の条件が偽なら後ろの条件は評価されない（短絡評価）ため「－」とした。
 *       前の条件が偽なら、後ろの条件の真偽にかかわらず結果は偽になる。
 *
 * 組み合わせ表2：ShiftTimeUtil#overlaps
 * 判定：A aStart &lt; bEnd && B bStart &lt; aEnd
 * No. | A | B | 結果 | テストの値（a / b）
 *  1  | 真| 真| 真   | 9:00-12:00 / 11:00-14:00
 *  2  | 真| 偽| 偽   | 12:00-14:00 / 9:00-12:00（終わりと始まりが同じ）
 *  3  | 偽| 真| 偽   | 9:00-12:00 / 12:00-14:00（終わりと始まりが同じ）
 *  4  | 偽| 偽| 偽   | 10:00-9:00 / 11:00-10:00（開始と終了が逆の時間帯。正しい時間帯では起きない）
 *
 * 組み合わせ表3：LikeUtil#contains
 * 判定：A keyword == null || B keyword が空
 * No. | A | B | 結果
 *  1  | 真| － | null（短絡評価で B は評価されない）
 *  2  | 偽| 真| null
 *  3  | 偽| 偽| %…%
 *
 * 組み合わせ表4：PasswordGenerator#isValid
 * 判定：A 英字がある && B 数字がある
 * No. | A | B | 結果 | テストの値
 *  1  | 真| 真| 真   | abc123
 *  2  | 真| 偽| 偽   | abcdef
 *  3  | 偽| 真| 偽   | 123456
 *  4  | 偽| 偽| 偽   | !#$%&amp;（記号だけ）、空文字
 *
 * 判定条件網羅：DateTimeUtil#shortTime（分が0か）、IcsUtil#escape・LikeUtil#escape（null か）、
 * IcsUtil#fold（75バイトを超えるか）、PasswordGenerator#generate（条件を満たすまでやり直す）は、
 * 真と偽の両方を下のテストで通す。
 * </pre>
 */
class UtilTests {

    // ===== ShiftTimeUtil =====

    @Test
    void timeOptions_8時から22時までの30分刻みで29個() {
        List<LocalTime> actual = ShiftTimeUtil.timeOptions();

        assertThat(actual).hasSize(29);
        assertThat(actual.get(0)).isEqualTo(LocalTime.of(8, 0));
        assertThat(actual.get(1)).isEqualTo(LocalTime.of(8, 30));
        assertThat(actual.get(28)).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void timeSelectOptions_値はHH_mmで表示はH_mmの29個() {
        List<SelectOption> actual = ShiftTimeUtil.timeSelectOptions();

        assertThat(actual).hasSize(29);
        assertThat(actual.get(0)).isEqualTo(new SelectOption("08:00", "8:00"));
        assertThat(actual.get(1)).isEqualTo(new SelectOption("08:30", "8:30"));
        assertThat(actual.get(28)).isEqualTo(new SelectOption("22:00", "22:00"));
    }

    @ParameterizedTest(name = "No.1 {0} は刻みの上")
    @CsvSource({ "08:00", "22:00", "13:30" })
    void isOnGrid_範囲内で刻みの上_true(LocalTime time) {
        boolean actual = ShiftTimeUtil.isOnGrid(time);

        assertThat(actual).isTrue();
    }

    @Test
    void isOnGrid_No2_nullはfalse() {
        boolean actual = ShiftTimeUtil.isOnGrid(null);

        assertThat(actual).isFalse();
    }

    @Test
    void isOnGrid_No3_下限の前はfalse() {
        boolean actual = ShiftTimeUtil.isOnGrid(LocalTime.of(7, 30));

        assertThat(actual).isFalse();
    }

    @Test
    void isOnGrid_No4_上限の後はfalse() {
        boolean actual = ShiftTimeUtil.isOnGrid(LocalTime.of(22, 30));

        assertThat(actual).isFalse();
    }

    @Test
    void isOnGrid_No5_分が刻み外はfalse() {
        boolean actual = ShiftTimeUtil.isOnGrid(LocalTime.of(8, 15));

        assertThat(actual).isFalse();
    }

    @Test
    void isOnGrid_No6_秒が0でないとfalse() {
        boolean actual = ShiftTimeUtil.isOnGrid(LocalTime.of(8, 0, 1));

        assertThat(actual).isFalse();
    }

    @Test
    void isOnGrid_No7_ナノ秒が0でないとfalse() {
        boolean actual = ShiftTimeUtil.isOnGrid(LocalTime.of(8, 0, 0, 1));

        assertThat(actual).isFalse();
    }

    @Test
    void overlaps_No1_重なるとtrue() {
        boolean actual = ShiftTimeUtil.overlaps(LocalTime.of(9, 0), LocalTime.of(12, 0),
                LocalTime.of(11, 0), LocalTime.of(14, 0));

        assertThat(actual).isTrue();
    }

    @Test
    void overlaps_No2_bの終わりとaの始まりが同じならfalse() {
        boolean actual = ShiftTimeUtil.overlaps(LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(9, 0), LocalTime.of(12, 0));

        assertThat(actual).isFalse();
    }

    @Test
    void overlaps_No3_aの終わりとbの始まりが同じならfalse() {
        boolean actual = ShiftTimeUtil.overlaps(LocalTime.of(9, 0), LocalTime.of(12, 0),
                LocalTime.of(12, 0), LocalTime.of(14, 0));

        assertThat(actual).isFalse();
    }

    @Test
    void overlaps_No4_両方の条件が偽ならfalse() {
        boolean actual = ShiftTimeUtil.overlaps(LocalTime.of(10, 0), LocalTime.of(9, 0),
                LocalTime.of(11, 0), LocalTime.of(10, 0));

        assertThat(actual).isFalse();
    }

    // ===== DateTimeUtil =====

    @Test
    void monthDay_月日と曜日() {
        String actual = DateTimeUtil.monthDay(LocalDate.of(2026, 10, 24));

        assertThat(actual).isEqualTo("10月24日(土)");
    }

    @Test
    void fullDate_年月日() {
        String actual = DateTimeUtil.fullDate(LocalDate.of(2026, 10, 24));

        assertThat(actual).isEqualTo("2026年10月24日");
    }

    @Test
    void dateTime_年月日と時分を0埋め() {
        String actual = DateTimeUtil.dateTime(LocalDateTime.of(2026, 1, 5, 9, 3));

        assertThat(actual).isEqualTo("2026/01/05 09:03");
    }

    @Test
    void time_時は0埋めしない() {
        assertThat(DateTimeUtil.time(LocalTime.of(8, 0))).isEqualTo("8:00");
        assertThat(DateTimeUtil.time(LocalTime.of(13, 30))).isEqualTo("13:30");
    }

    @Test
    void period_開始と終了を波ダッシュでつなぐ() {
        String actual = DateTimeUtil.period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15));

        assertThat(actual).isEqualTo("11/1〜11/15");
    }

    @Test
    void shortDate_月と日() {
        String actual = DateTimeUtil.shortDate(LocalDate.of(2026, 10, 15));

        assertThat(actual).isEqualTo("10/15");
    }

    @Test
    void dayOfWeek_曜日だけ() {
        assertThat(DateTimeUtil.dayOfWeek(LocalDate.of(2026, 11, 1))).isEqualTo("日");
        assertThat(DateTimeUtil.dayOfWeek(LocalDate.of(2026, 11, 7))).isEqualTo("土");
    }

    @Test
    void shortDateWithDay_月日と曜日を0埋めしない() {
        assertThat(DateTimeUtil.shortDateWithDay(LocalDate.of(2026, 11, 1))).isEqualTo("11/1(日)");
        assertThat(DateTimeUtil.shortDateWithDay(LocalDate.of(2026, 12, 16))).isEqualTo("12/16(水)");
    }

    @Test
    void timeRange_開始と終了を時は0埋めせず波ダッシュでつなぐ() {
        assertThat(DateTimeUtil.timeRange(LocalTime.of(8, 0), LocalTime.of(17, 0))).isEqualTo("8:00〜17:00");
        assertThat(DateTimeUtil.timeRange(LocalTime.of(13, 30), LocalTime.of(22, 5))).isEqualTo("13:30〜22:05");
    }

    @Test
    void yearMonth_年と月を0埋めしない() {
        assertThat(DateTimeUtil.yearMonth(YearMonth.of(2026, 1))).isEqualTo("2026年1月");
        assertThat(DateTimeUtil.yearMonth(YearMonth.of(2026, 10))).isEqualTo("2026年10月");
    }

    @Test
    void day_日だけを0埋めしない() {
        assertThat(DateTimeUtil.day(LocalDate.of(2026, 11, 1))).isEqualTo("1");
        assertThat(DateTimeUtil.day(LocalDate.of(2026, 10, 24))).isEqualTo("24");
    }

    @Test
    void shortYearDate_年月日を0埋めしない() {
        String actual = DateTimeUtil.shortYearDate(LocalDate.of(2025, 12, 3));

        assertThat(actual).isEqualTo("2025/12/3");
    }

    @Test
    void shortDateTime_月日と時分() {
        String actual = DateTimeUtil.shortDateTime(LocalDateTime.of(2026, 10, 15, 23, 59));

        assertThat(actual).isEqualTo("10/15 23:59");
    }

    @Test
    void dateTimeSeconds_秒まで() {
        String actual = DateTimeUtil.dateTimeSeconds(LocalDateTime.of(2026, 10, 22, 9, 5, 31));

        assertThat(actual).isEqualTo("2026/10/22 09:05:31");
    }

    @Test
    void printPeriod_開始は年付きで曜日も出す() {
        String actual = DateTimeUtil.printPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15));

        assertThat(actual).isEqualTo("2026/11/1(日)〜11/15(日)");
    }

    @Test
    void shortRange_分が0なら時だけ() {
        String actual = DateTimeUtil.shortRange(LocalTime.of(13, 0), LocalTime.of(22, 0));

        assertThat(actual).isEqualTo("13-22");
    }

    @Test
    void shortRange_分が0でなければ時分() {
        assertThat(DateTimeUtil.shortRange(LocalTime.of(13, 30), LocalTime.of(22, 0))).isEqualTo("13:30-22");
        assertThat(DateTimeUtil.shortRange(LocalTime.of(8, 0), LocalTime.of(12, 30))).isEqualTo("8-12:30");
    }

    // ===== LikeUtil =====

    @Test
    void escape_nullはnull() {
        String actual = LikeUtil.escape(null);

        assertThat(actual).isNull();
    }

    @Test
    void escape_ビックリマークとパーセントと下線の前にビックリマークを付ける() {
        String actual = LikeUtil.escape("a!b%c_d");

        assertThat(actual).isEqualTo("a!!b!%c!_d");
    }

    @Test
    void escape_特別な文字がなければそのまま() {
        String actual = LikeUtil.escape("山田");

        assertThat(actual).isEqualTo("山田");
    }

    @Test
    void contains_No1_nullはnull() {
        String actual = LikeUtil.contains(null);

        assertThat(actual).isNull();
    }

    @Test
    void contains_No2_空文字はnull() {
        String actual = LikeUtil.contains("");

        assertThat(actual).isNull();
    }

    @Test
    void contains_No3_前後にパーセントを付けてエスケープする() {
        String actual = LikeUtil.contains("10%");

        assertThat(actual).isEqualTo("%10!%%");
    }

    // ===== IcsUtil =====

    @Test
    void escape_ics_nullは空文字() {
        String actual = IcsUtil.escape(null);

        assertThat(actual).isEmpty();
    }

    @Test
    void escape_ics_円記号とセミコロンとカンマと改行をエスケープする() {
        String actual = IcsUtil.escape("a\\b;c,d\r\ne\nf\rg");

        assertThat(actual).isEqualTo("a\\\\b\\;c\\,d\\ne\\nf\\ng");
    }

    @Test
    void fold_75バイトちょうどは折り返さない() {
        String line = "a".repeat(75);

        String actual = IcsUtil.fold(line);

        assertThat(actual).isEqualTo(line);
    }

    @Test
    void fold_76バイトは75バイトで折り返す() {
        String actual = IcsUtil.fold("a".repeat(76));

        assertThat(actual).isEqualTo("a".repeat(75) + "\r\n " + "a");
    }

    @Test
    void fold_2行目は先頭の空白を含めて75バイトで折り返す() {
        String actual = IcsUtil.fold("a".repeat(75 + 74 + 1));

        assertThat(actual).isEqualTo("a".repeat(75) + "\r\n " + "a".repeat(74) + "\r\n " + "a");
    }

    @Test
    void fold_日本語25文字は75バイトなので折り返さない() {
        String line = "あ".repeat(25);

        String actual = IcsUtil.fold(line);

        assertThat(actual).isEqualTo(line);
    }

    @Test
    void fold_日本語は文字の途中で切らない() {
        // 「a」1バイト＋「あ」3バイト×25＝76バイト。25文字目の「あ」は75バイトに入らないので次の行へ
        String actual = IcsUtil.fold("a" + "あ".repeat(25));

        assertThat(actual).isEqualTo("a" + "あ".repeat(24) + "\r\n " + "あ");
    }

    @Test
    void fold_絵文字のサロゲートペアを分けない() {
        // 「a」72バイト＋絵文字4バイト＝76バイト
        String emoji = "😀";

        String actual = IcsUtil.fold("a".repeat(72) + emoji);

        assertThat(actual).isEqualTo("a".repeat(72) + "\r\n " + emoji);
    }

    @Test
    void fold_空文字は空文字() {
        String actual = IcsUtil.fold("");

        assertThat(actual).isEmpty();
    }

    @Test
    void utc_日本時間から9時間引く() {
        String actual = IcsUtil.utc(LocalDate.of(2026, 11, 1), LocalTime.of(13, 30));

        assertThat(actual).isEqualTo("20261101T043000Z");
    }

    @Test
    void utc_9時より前は前の日になる() {
        String actual = IcsUtil.utc(LocalDate.of(2026, 11, 1), LocalTime.of(8, 0));

        assertThat(actual).isEqualTo("20261031T230000Z");
    }

    // ===== PasswordGenerator =====

    @Test
    void generate_10文字で使う文字だけ_英字と数字を両方含む() {
        PasswordGenerator generator = new PasswordGenerator();

        for (int i = 0; i < 100; i++) {
            String actual = generator.generate();

            assertThat(actual).hasSize(PasswordGenerator.LENGTH);
            assertThat(actual.chars()).allMatch(c -> PasswordGenerator.CHARS.indexOf(c) >= 0);
            assertThat(PasswordGenerator.isValid(actual)).isTrue();
        }
    }

    @Test
    void generate_英字だけになったら作り直す() throws Exception {
        PasswordGenerator generator = new PasswordGenerator();
        // 1回目は10文字とも先頭の「A」（英字だけ）、2回目は「A」と最後の「9」が交互になる乱数
        int nine = PasswordGenerator.CHARS.length() - 1;
        int[] sequence = new int[20];
        for (int i = 10; i < 20; i++) {
            sequence[i] = i % 2 == 0 ? 0 : nine;
        }
        replaceRandom(generator, new SequenceRandom(sequence));

        String actual = generator.generate();

        assertThat(actual).isEqualTo("A9A9A9A9A9");
    }

    @Test
    void CHARS_見まちがえやすい文字を含まない() {
        assertThat(PasswordGenerator.CHARS).doesNotContain("I", "O", "l", "0", "1");
    }

    @Test
    void isValid_No1_英字と数字があればtrue() {
        assertThat(PasswordGenerator.isValid("abc123")).isTrue();
    }

    @Test
    void isValid_No2_英字だけはfalse() {
        assertThat(PasswordGenerator.isValid("abcdef")).isFalse();
    }

    @Test
    void isValid_No3_数字だけはfalse() {
        assertThat(PasswordGenerator.isValid("123456")).isFalse();
    }

    @Test
    void isValid_No4_記号だけと空文字はfalse() {
        assertThat(PasswordGenerator.isValid("!#$%&")).isFalse();
        assertThat(PasswordGenerator.isValid("")).isFalse();
    }

    // ===== TokenGenerator =====

    @Test
    void generate_token_Base64URLの43文字() {
        String actual = new TokenGenerator().generate();

        assertThat(actual).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void generate_token_毎回違う値() {
        TokenGenerator generator = new TokenGenerator();
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            tokens.add(generator.generate());
        }

        assertThat(tokens).hasSize(100);
    }

    // ===== インスタンスを作らないクラス =====

    @Test
    void 定数だけのクラスはprivateのコンストラクター() throws Exception {
        for (Class<?> type : List.of(ShiftTimeUtil.class, DateTimeUtil.class, LikeUtil.class, IcsUtil.class)) {
            Constructor<?> constructor = type.getDeclaredConstructor();

            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }
    }

    /** PasswordGenerator の乱数を、決まった順の値を返すものに入れ替える。 */
    private static void replaceRandom(PasswordGenerator generator, SecureRandom random) throws Exception {
        Field field = PasswordGenerator.class.getDeclaredField("random");
        field.setAccessible(true);
        field.set(generator, random);
    }

    /** 決まった順に値を返す乱数（テスト用）。 */
    private static final class SequenceRandom extends SecureRandom {

        private static final long serialVersionUID = 1L;

        private final transient int[] values;
        private int index;

        SequenceRandom(int[] values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            return values[index++];
        }
    }
}
