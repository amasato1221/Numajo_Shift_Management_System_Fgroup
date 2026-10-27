package jp.numajo.fgroup.shift.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.CalendarFeed;

/**
 * カレンダー購読（calendar_feeds）のリポジトリ。主キーはユーザーID。
 */
public interface CalendarFeedRepository extends JpaRepository<CalendarFeed, Long> {

    /** トークンで取得する（IC-01）。 */
    Optional<CalendarFeed> findByToken(String token);

    /** トークンが使われているか。 */
    boolean existsByToken(String token);
}
