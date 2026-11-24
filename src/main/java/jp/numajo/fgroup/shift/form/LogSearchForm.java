package jp.numajo.fgroup.shift.form;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import jp.numajo.fgroup.shift.constant.OperationAction;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SA-03 操作ログの検索条件（GET。20-SA-03 1.1、30-07）。
 *
 * <p>
 * 入力チェックは付けない。型の合わない値（日付の形違い、列挙にない action）は null のままにし、
 * 既定値は AdminLogController で入れる。
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
public class LogSearchForm {

    /** 期間（から）。この日の 0:00:00 以降 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    /** 期間（まで）。この日の 23:59:59 まで */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    /** 利用者のID（null＝すべて） */
    private Long userId;

    /** 操作（null＝すべて） */
    private OperationAction action;

    /** ページ番号（0から） */
    private Integer page;
}
