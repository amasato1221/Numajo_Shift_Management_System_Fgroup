package jp.numajo.fgroup.shift.dto;

import java.util.List;

/**
 * スタッフ一覧（SM-02）の1行分。
 *
 * @param id        ユーザーID
 * @param name      氏名
 * @param nameKana  ふりがな
 * @param loginId   ログインID
 * @param skillNames 職能名（名前順）
 * @param trainee   研修中か
 * @param active    有効か
 * @param locked    ロック中か
 */
public record StaffRowView(Long id, String name, String nameKana, String loginId, List<String> skillNames,
        boolean trainee, boolean active, boolean locked) {
}
