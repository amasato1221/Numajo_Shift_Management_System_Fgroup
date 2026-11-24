package jp.numajo.fgroup.shift.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import jp.numajo.fgroup.shift.constant.BackupStatus;

/**
 * BackupRow#sizeText の単体テスト（30-07 4.2、20-SA-04 1章 No.3）。
 *
 * <pre>
 * 判定             | 真：「－」 | 偽：KB（小数1けた）
 * fileSize == null | 失敗の行   | 0・1023・1024・1536・大きな値
 * </pre>
 */
class BackupRowTests {

    private static BackupRow row(Long size) {
        return new BackupRow(1L, null, null, "a.sql", size, BackupStatus.SUCCESS, "a");
    }

    @Test
    void 大きさなし_ハイフン() {
        assertThat(row(null).sizeText()).isEqualTo("－");
    }

    @ParameterizedTest(name = "{0} バイト → {1}")
    @CsvSource(delimiter = '|', value = {
        "0       | 0.0 KB",
        "1023    | 1.0 KB",
        "1024    | 1.0 KB",
        "1536    | 1.5 KB",
        "1300234 | 1269.8 KB" })
    void 大きさあり_KBで小数1けた(long size, String expected) {
        assertThat(row(size).sizeText()).isEqualTo(expected);
    }
}
