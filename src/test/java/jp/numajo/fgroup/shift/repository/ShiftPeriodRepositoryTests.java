package jp.numajo.fgroup.shift.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.annotation.Transactional;

import jp.numajo.fgroup.shift.constant.PeriodStatus;
import jp.numajo.fgroup.shift.entity.ShiftPeriod;
import lombok.RequiredArgsConstructor;

/**
 * ShiftPeriodRepository#findByStatusAndDeadlineAtBefore の統合テスト
 * （BT-02。95_通知・定期処理設計書 3.2、97_テスト計画書 4.1）。
 *
 * <p>
 * 「期限ちょうどは締めない」は、メソッド名の Before（より前）で決まる。モックでは確かめられないので、
 * shift_test のDBに実際にSQLを流して確かめる。テストごとにロールバックする。
 * ほかのテストが作った期間と開始日が重ならないよう、2031年の期間を使う。
 * 表の No. は、テストメソッドを上から数えた順で、1対1で対応する。
 * </p>
 *
 * <pre>
 * 判定：状態が OPEN か（S）、期限が now より前か（B）
 *
 * No. | S      | B                    | 結果
 *  1  | 真     | 偽（now＝期限ちょうど） | 含まない
 *  2  | 真     | 真（now＝期限の1秒後）  | 含む
 *  3  | 偽     | 真                   | 含まない（作成中・公開済み）
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class ShiftPeriodRepositoryTests {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2031, 2, 28, 23, 59, 59);

    private final ShiftPeriodRepository repository;

    @Test
    void 受付中で現在が期限ちょうど_締切の対象に含まない() {
        ShiftPeriod period = save(LocalDate.of(2031, 3, 1), PeriodStatus.OPEN);

        List<ShiftPeriod> actual = repository.findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN, DEADLINE);

        assertThat(actual).extracting(ShiftPeriod::getId).doesNotContain(period.getId());
    }

    @Test
    void 受付中で現在が期限の1秒後_締切の対象に含む() {
        ShiftPeriod period = save(LocalDate.of(2031, 3, 1), PeriodStatus.OPEN);

        List<ShiftPeriod> actual = repository.findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN,
                DEADLINE.plusSeconds(1));

        assertThat(actual).extracting(ShiftPeriod::getId).contains(period.getId());
    }

    @Test
    void 受付中でなく期限を過ぎている_締切の対象に含まない() {
        ShiftPeriod closed = save(LocalDate.of(2031, 3, 1), PeriodStatus.CLOSED);
        ShiftPeriod published = save(LocalDate.of(2031, 3, 16), PeriodStatus.PUBLISHED);

        List<ShiftPeriod> actual = repository.findByStatusAndDeadlineAtBefore(PeriodStatus.OPEN,
                DEADLINE.plusSeconds(1));

        assertThat(actual).extracting(ShiftPeriod::getId)
                .doesNotContain(closed.getId(), published.getId());
    }

    private ShiftPeriod save(LocalDate startDate, PeriodStatus status) {
        ShiftPeriod period = new ShiftPeriod();
        period.setStartDate(startDate);
        period.setEndDate(startDate.plusDays(14));
        period.setDeadlineAt(DEADLINE);
        period.setStatus(status);
        return repository.saveAndFlush(period);
    }
}
