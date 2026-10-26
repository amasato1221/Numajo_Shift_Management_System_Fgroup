package jp.numajo.fgroup.shift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * messages.properties（93_メッセージ一覧 3章）の中身のテスト。
 *
 * <p>
 * 判定のあるコードがないため、4つの網羅の対象はない。93 の決まり（170件、コードの形、シングルクォートを使わない）を確かめる。
 * </p>
 */
class MessagesPropertiesTests {

    /** コメントと空行を除いた行を読む。 */
    private List<String> readEntries() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/messages.properties")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> entries = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    entries.add(line);
                }
            }
            return entries;
        }
    }

    @Test
    void 全文を読む_170件ある() throws IOException {
        assertEquals(170, readEntries().size());
    }

    @Test
    void 全文を読む_コードが重ならずEWICNと数字4桁の形になっている() throws IOException {
        Set<String> codes = new HashSet<>();
        for (String entry : readEntries()) {
            String code = entry.substring(0, entry.indexOf('='));
            assertTrue(code.matches("[EWICN][0-9]{4}"), entry);
            assertTrue(codes.add(code), "コードが重なっている：" + code);
        }
    }

    @Test
    void 全文を読む_シングルクォートを含まない() throws IOException {
        for (String entry : readEntries()) {
            assertFalse(entry.contains("'"), entry);
        }
    }
}
