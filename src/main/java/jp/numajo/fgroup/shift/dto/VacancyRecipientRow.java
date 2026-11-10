package jp.numajo.fgroup.shift.dto;

/**
 * 欠員通知の相手（SM-07）の1行分。
 *
 * @param userId  ユーザーID
 * @param name    氏名
 * @param trainee 研修中か
 */
public record VacancyRecipientRow(Long userId, String name, boolean trainee) {
}
