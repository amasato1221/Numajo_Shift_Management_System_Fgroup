package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jp.numajo.fgroup.shift.constant.StaffStatusFilter;
import jp.numajo.fgroup.shift.dto.StaffRowView;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.User;
import jp.numajo.fgroup.shift.repository.UserRepository;

/**
 * StaffService（SM-02：スタッフ一覧）のテスト。
 *
 * <table>
 * <caption>ロック中の判定（lockedUntil と今の比べ方）</caption>
 * <tr><th>lockedUntil</th><th>ロック中</th></tr>
 * <tr><td>null</td><td>いいえ</td></tr>
 * <tr><td>今より前</td><td>いいえ</td></tr>
 * <tr><td>今と同じ</td><td>いいえ</td></tr>
 * <tr><td>今より後</td><td>はい</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class StaffServiceTests {

    /** テストの「今」。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2099, 1, 1, 10, 0);

    @Mock
    private UserRepository userRepository;

    private StaffService service;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        service = new StaffService(userRepository, clock);
    }

    private User user(Long id, String name, LocalDateTime lockedUntil, String... skillNames) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setNameKana("かな" + id);
        user.setLoginId("login" + id);
        user.setTrainee(id % 2 == 0);
        user.setActive(true);
        user.setLockedUntil(lockedUntil);
        Set<Skill> skills = new HashSet<>();
        for (String skillName : skillNames) {
            Skill skill = new Skill();
            skill.setName(skillName);
            skills.add(skill);
        }
        user.setSkills(skills);
        return user;
    }

    @Test
    void search_ユーザーを一覧の行に変換する() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of(user(1L, "山田 太郎", null, "レジ")));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ACTIVE);

        assertThat(actual).hasSize(1);
        StaffRowView row = actual.get(0);
        assertThat(row.id()).isEqualTo(1L);
        assertThat(row.name()).isEqualTo("山田 太郎");
        assertThat(row.nameKana()).isEqualTo("かな1");
        assertThat(row.loginId()).isEqualTo("login1");
        assertThat(row.trainee()).isFalse();
        assertThat(row.active()).isTrue();
    }

    @Test
    void search_該当なしなら空のリストを返す() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of());

        List<StaffRowView> actual = service.search("zzz", StaffStatusFilter.ALL);

        assertThat(actual).isEmpty();
    }

    @Test
    void search_リポジトリの順番のまま返す() {
        when(userRepository.searchStaff(any(), any()))
                .thenReturn(List.of(user(3L, "三", null), user(1L, "一", null), user(2L, "二", null)));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ACTIVE);

        assertThat(actual).extracting(StaffRowView::id).containsExactly(3L, 1L, 2L);
    }

    @Test
    void search_キーワードなしならパターンはnull() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of());

        service.search(null, StaffStatusFilter.ACTIVE);

        verify(userRepository).searchStaff(isNull(), any());
    }

    @Test
    void search_キーワードは前後をパーセントで挟んだパターンにする() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of());

        service.search("やま", StaffStatusFilter.ACTIVE);

        verify(userRepository).searchStaff("%やま%", true);
    }

    @Test
    void search_パーセントを含むキーワードはエスケープしてから渡す() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of());

        service.search("100%", StaffStatusFilter.ACTIVE);

        verify(userRepository).searchStaff("%100!%%", true);
    }

    @Test
    void search_ACTIVEならtrueを渡す() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of());

        service.search(null, StaffStatusFilter.ACTIVE);

        verify(userRepository).searchStaff(isNull(), eq(true));
    }

    @Test
    void search_INACTIVEならfalseを渡す() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of());

        service.search(null, StaffStatusFilter.INACTIVE);

        verify(userRepository).searchStaff(isNull(), eq(false));
    }

    @Test
    void search_ALLならnullを渡す() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of());

        service.search(null, StaffStatusFilter.ALL);

        verify(userRepository).searchStaff(isNull(), isNull());
    }

    @Test
    void search_ロック解除日時がnullならロック中ではない() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of(user(1L, "a", null)));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ALL);

        assertThat(actual.get(0).locked()).isFalse();
    }

    @Test
    void search_ロック解除日時が今より前ならロック中ではない() {
        when(userRepository.searchStaff(any(), any()))
                .thenReturn(List.of(user(1L, "a", NOW.minusMinutes(1))));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ALL);

        assertThat(actual.get(0).locked()).isFalse();
    }

    @Test
    void search_ロック解除日時が今と同じならロック中ではない() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of(user(1L, "a", NOW)));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ALL);

        assertThat(actual.get(0).locked()).isFalse();
    }

    @Test
    void search_ロック解除日時が今より後ならロック中() {
        when(userRepository.searchStaff(any(), any()))
                .thenReturn(List.of(user(1L, "a", NOW.plusMinutes(1))));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ALL);

        assertThat(actual.get(0).locked()).isTrue();
    }

    @Test
    void search_職能名は名前順に並べる() {
        when(userRepository.searchStaff(any(), any()))
                .thenReturn(List.of(user(1L, "a", null, "レジ", "ドリンク", "フライヤー")));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ALL);

        assertThat(actual.get(0).skillNames()).containsExactly("ドリンク", "フライヤー", "レジ");
    }

    @Test
    void search_職能がなければ空のリスト() {
        when(userRepository.searchStaff(any(), any())).thenReturn(List.of(user(1L, "a", null)));

        List<StaffRowView> actual = service.search(null, StaffStatusFilter.ALL);

        assertThat(actual.get(0).skillNames()).isEmpty();
    }
}
