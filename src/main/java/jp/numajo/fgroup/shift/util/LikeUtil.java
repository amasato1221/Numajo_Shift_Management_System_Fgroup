package jp.numajo.fgroup.shift.util;

/**
 * LIKE 検索の共通処理。エスケープ文字は「!」（JPQL で ESCAPE '!' と書く）。
 */
public final class LikeUtil {

    private LikeUtil() {
    }

    /** 「!」「%」「_」の前に「!」を付ける。null は null。 */
    public static String escape(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** 部分一致のパターン「%…%」を作る。null・空は null（絞り込まない）。 */
    public static String contains(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        return "%" + escape(keyword) + "%";
    }
}
