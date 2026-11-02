package jp.numajo.fgroup.shift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.numajo.fgroup.shift.constant.OperationAction;
import jp.numajo.fgroup.shift.constant.SlotIds;
import jp.numajo.fgroup.shift.dto.SkillRowView;
import jp.numajo.fgroup.shift.entity.Skill;
import jp.numajo.fgroup.shift.entity.Slot;
import jp.numajo.fgroup.shift.exception.BusinessException;
import jp.numajo.fgroup.shift.exception.ResourceNotFoundException;
import jp.numajo.fgroup.shift.form.SlotSkillForm;
import jp.numajo.fgroup.shift.repository.SkillRepository;
import jp.numajo.fgroup.shift.repository.SlotRepository;

/**
 * SkillService の単体テスト（20-SM-04 2章・6章、97_テスト計画書 4.1）。
 *
 * <p>
 * 判定は、1つずつの条件を真と偽で確かめる（条件網羅）。複数の条件が組み合わさる判定はないので、
 * 組み合わせ表は、判定ごとに真・偽の行を並べた下の表にする（97_テスト計画書 3.2）。
 * Repository と OperationLogService はモックにする。
 * 表の No. は、テストメソッドを上から数えた順で、1対1で対応する。
 * </p>
 *
 * <pre>
 * 判定の記号
 *   A＝リストが null か          B＝リストの要素が null か    C＝取れた職能の数 != 選んだ数
 *   D＝枠のID が 日勤            E＝枠のID が 夕勤（D が偽のとき）
 *
 * No. | メソッド           | 判定                   | 値              | 結果
 *  1  | findAll           | －                     | －              | 名前順の全件を返す
 *  2  | findRows          | 人数の集計にある        | 真・偽          | 人数／0人
 *  3  | getSlotSkillForm  | D、E                   | 真／偽→真       | 日勤の欄／夕勤の欄に職能ID順で入れる
 *  4  | getSlotSkillForm  | 必要職能が空            | 真              | 日勤も夕勤も空のリスト
 *  5  | create            | existsByName           | 真              | E2404、保存もログもなし
 *  6  | create            | existsByName           | 偽              | 保存して SKILL_CREATE
 *  7  | update            | findById が空          | 真              | ResourceNotFound
 *  8  | update            | existsByNameAndIdNot   | 真              | E2404、上書きしない
 *  9  | update            | 上の2つ                | 偽・偽          | 上書きして SKILL_UPDATE
 * 10  | update            | existsByNameAndIdNot   | 偽（自分は除く） | 同じ名前のまま説明だけ変わる
 * 11  | delete            | findById が空          | 真              | ResourceNotFound
 * 12  | delete            | findById が空          | 偽              | 削除して SKILL_DELETE
 * 13  | saveSlotRequired  | C                      | 真              | E2405、枠もログも変えない
 * 14  | saveSlotRequired  | A、B、C、D、E           | 偽・偽・偽・真・真 | 日勤と夕勤を置き換えてログ
 * 15  | saveSlotRequired  | A                      | 真              | 空として扱い全部外す
 * 16  | saveSlotRequired  | 重複した職能ID          | －              | 1つとして扱う
 * 17  | 両方              | D、E                   | 偽・偽          | 読み取りも置き換えもしない
 * 18  | saveSlotRequired  | B                      | 真              | null を無視する
 *
 * テストメソッドは、この表の No. の順に並べている。名前は「条件_期待する結果」の形。
 * </pre>
 */
class SkillServiceTests {

    private static final Long OPERATOR = 9L;

    private SkillRepository skillRepository;
    private SlotRepository slotRepository;
    private OperationLogService operationLogService;
    private SkillService service;

    @BeforeEach
    void setUp() {
        skillRepository = mock(SkillRepository.class);
        slotRepository = mock(SlotRepository.class);
        operationLogService = mock(OperationLogService.class);
        service = new SkillService(skillRepository, slotRepository, operationLogService);
    }

    private static Skill skill(Long id, String name, String description) {
        Skill s = new Skill();
        s.setId(id);
        s.setName(name);
        s.setDescription(description);
        return s;
    }

    private static Slot slot(byte id, Skill... required) {
        Slot s = new Slot();
        s.setId(id);
        s.setRequiredSkills(new HashSet<>(List.of(required)));
        return s;
    }

    // ---------------------------------------------------------------- 一覧・初期値

    @Test
    void 職能が登録されている_名前順の全件を返す() {
        List<Skill> skills = List.of(skill(1L, "レジ", null));
        when(skillRepository.findAllByOrderByNameAscIdAsc()).thenReturn(skills);

        assertThat(service.findAll()).isSameAs(skills);
    }

    @Test
    void 持っている人がいる職能といない職能がある_人数を付け持つ人がいなければ0人にする() {
        when(skillRepository.findAllByOrderByNameAscIdAsc())
                .thenReturn(List.of(skill(1L, "揚げ物", "フライヤー"), skill(2L, "レジ", null)));
        when(skillRepository.countActiveStaffPerSkill())
                .thenReturn(java.util.Collections.singletonList(new Object[] { 2L, 3L }));

        List<SkillRowView> rows = service.findRows();

        assertThat(rows).containsExactly(
                new SkillRowView(1L, "揚げ物", "フライヤー", 0L),
                new SkillRowView(2L, "レジ", null, 3L));
    }

    @Test
    void 日勤と夕勤に必要職能がある_職能ID順で日勤と夕勤に分けて返す() {
        Skill a = skill(1L, "A", null);
        Skill b = skill(2L, "B", null);
        Skill c = skill(3L, "C", null);
        when(slotRepository.findAllWithRequiredSkills())
                .thenReturn(List.of(slot(SlotIds.DAY, c, a), slot(SlotIds.EVENING, b)));

        SlotSkillForm form = service.getSlotSkillForm();

        assertThat(form.getDaySkillIds()).containsExactly(1L, 3L);
        assertThat(form.getEveningSkillIds()).containsExactly(2L);
    }

    @Test
    void 必要職能がない_日勤も夕勤も空のリストを返す() {
        when(slotRepository.findAllWithRequiredSkills())
                .thenReturn(List.of(slot(SlotIds.DAY), slot(SlotIds.EVENING)));

        SlotSkillForm form = service.getSlotSkillForm();

        assertThat(form.getDaySkillIds()).isEmpty();
        assertThat(form.getEveningSkillIds()).isEmpty();
    }

    // ---------------------------------------------------------------- 追加

    @Test
    void 同じ名前の職能がある_追加するとE2404になり保存もログも残さない() {
        when(skillRepository.existsByName("レジ")).thenReturn(true);

        assertThatThrownBy(() -> service.create("レジ", null, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2404");

        verify(skillRepository, never()).save(any());
        verifyNoInteractions(operationLogService);
    }

    @Test
    void 同じ名前の職能がない_追加すると保存して操作ログSKILL_CREATEを残す() {
        when(skillRepository.existsByName("レジ")).thenReturn(false);
        when(skillRepository.save(any(Skill.class))).thenAnswer(invocation -> {
            Skill saved = invocation.getArgument(0);
            saved.setId(5L);
            return saved;
        });

        service.create("レジ", "会計", OPERATOR);

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(skillRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("レジ");
        assertThat(captor.getValue().getDescription()).isEqualTo("会計");
        verify(operationLogService).record(OPERATOR, OperationAction.SKILL_CREATE, "skill:5", "レジ");
    }

    // ---------------------------------------------------------------- 変更

    @Test
    void 変更する職能が存在しない_ResourceNotFoundExceptionになる() {
        when(skillRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(5L, "新", null, OPERATOR))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(operationLogService);
    }

    @Test
    void ほかの職能と同じ名前に変更する_E2404になり上書きしない() {
        Skill target = skill(5L, "旧", "説明");
        when(skillRepository.findById(5L)).thenReturn(Optional.of(target));
        when(skillRepository.existsByNameAndIdNot("新", 5L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(5L, "新", "別の説明", OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2404");

        assertThat(target.getName()).isEqualTo("旧");
        assertThat(target.getDescription()).isEqualTo("説明");
        verifyNoInteractions(operationLogService);
    }

    @Test
    void 名前と説明を変更する_上書きして操作ログSKILL_UPDATEに古い名前と新しい名前を残す() {
        Skill target = skill(5L, "旧", "説明");
        when(skillRepository.findById(5L)).thenReturn(Optional.of(target));
        when(skillRepository.existsByNameAndIdNot("新", 5L)).thenReturn(false);

        service.update(5L, "新", "新しい説明", OPERATOR);

        assertThat(target.getName()).isEqualTo("新");
        assertThat(target.getDescription()).isEqualTo("新しい説明");
        verify(operationLogService).record(OPERATOR, OperationAction.SKILL_UPDATE, "skill:5", "旧 → 新");
    }

    @Test
    void 自分と同じ名前のまま説明だけ変更する_変更できる() {
        Skill target = skill(5L, "レジ", "旧");
        when(skillRepository.findById(5L)).thenReturn(Optional.of(target));
        // 自分自身は除いて探すので、同じ名前は見つからない
        when(skillRepository.existsByNameAndIdNot("レジ", 5L)).thenReturn(false);

        service.update(5L, "レジ", "新", OPERATOR);

        assertThat(target.getDescription()).isEqualTo("新");
        verify(operationLogService).record(OPERATOR, OperationAction.SKILL_UPDATE, "skill:5", "レジ → レジ");
    }

    // ---------------------------------------------------------------- 削除

    @Test
    void 削除する職能が存在しない_ResourceNotFoundExceptionになる() {
        when(skillRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(5L, OPERATOR))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(skillRepository, never()).delete(any());
        verifyNoInteractions(operationLogService);
    }

    @Test
    void 職能を削除する_消して操作ログSKILL_DELETEに名前を残す() {
        Skill target = skill(5L, "レジ", null);
        when(skillRepository.findById(5L)).thenReturn(Optional.of(target));

        service.delete(5L, OPERATOR);

        verify(skillRepository).delete(target);
        verify(operationLogService).record(OPERATOR, OperationAction.SKILL_DELETE, "skill:5", "レジ");
    }

    // ---------------------------------------------------------------- 枠の必要職能

    @Test
    void 存在しない職能IDを選ぶ_E2405になり枠も変えずログも残さない() {
        Skill a = skill(1L, "A", null);
        Slot day = slot(SlotIds.DAY, a);
        when(skillRepository.findAllById(any())).thenReturn(List.of(a));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(day));

        assertThatThrownBy(() -> service.saveSlotRequired(List.of(1L, 99L), List.of(), OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2405");

        assertThat(day.getRequiredSkills()).containsExactly(a);
        verifyNoInteractions(operationLogService);
    }

    @Test
    void 日勤と夕勤の職能を選んで保存する_それぞれ置き換えて操作ログを残す() {
        Skill a = skill(1L, "A", null);
        Skill b = skill(2L, "B", null);
        Skill c = skill(3L, "C", null);
        Slot day = slot(SlotIds.DAY, c);
        Slot evening = slot(SlotIds.EVENING, a);
        when(skillRepository.findAllById(any())).thenReturn(List.of(a, b));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(day, evening));

        service.saveSlotRequired(List.of(1L, 2L), List.of(2L), OPERATOR);

        assertThat(day.getRequiredSkills()).containsExactlyInAnyOrder(a, b);
        assertThat(evening.getRequiredSkills()).containsExactly(b);
        verify(operationLogService).record(OPERATOR, OperationAction.SLOT_SKILL_UPDATE, null,
                "DAY=[1, 2], EVENING=[2]");
    }

    @Test
    void 職能IDのリストがnull_空として扱い全部外す() {
        Skill a = skill(1L, "A", null);
        Slot day = slot(SlotIds.DAY, a);
        Slot evening = slot(SlotIds.EVENING, a);
        when(skillRepository.findAllById(any())).thenReturn(List.of());
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(day, evening));

        service.saveSlotRequired(null, null, OPERATOR);

        assertThat(day.getRequiredSkills()).isEmpty();
        assertThat(evening.getRequiredSkills()).isEmpty();
        verify(operationLogService).record(OPERATOR, OperationAction.SLOT_SKILL_UPDATE, null,
                "DAY=[], EVENING=[]");
    }

    @Test
    void 同じ職能IDが重なっている_1つとして扱う() {
        Skill a = skill(1L, "A", null);
        Slot day = slot(SlotIds.DAY);
        when(skillRepository.findAllById(any())).thenReturn(List.of(a));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(day));

        service.saveSlotRequired(List.of(1L, 1L), List.of(), OPERATOR);

        Set<Skill> required = day.getRequiredSkills();
        assertThat(required).containsExactly(a);
        verify(operationLogService).record(OPERATOR, OperationAction.SLOT_SKILL_UPDATE, null,
                "DAY=[1], EVENING=[]");
    }

    @Test
    void 日勤と夕勤以外の枠がある_読み取りも置き換えもしない() {
        Skill a = skill(1L, "A", null);
        Slot other = slot((byte) 3, a);
        when(skillRepository.findAllById(any())).thenReturn(List.of(a));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(other));

        SlotSkillForm form = service.getSlotSkillForm();
        service.saveSlotRequired(List.of(1L), List.of(1L), OPERATOR);

        assertThat(form.getDaySkillIds()).isEmpty();
        assertThat(form.getEveningSkillIds()).isEmpty();
        assertThat(other.getRequiredSkills()).containsExactly(a);
    }

    @Test
    void 職能IDのリストにnullが含まれる_nullを無視する() {
        Skill a = skill(1L, "A", null);
        Slot day = slot(SlotIds.DAY);
        when(skillRepository.findAllById(any())).thenReturn(List.of(a));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(day));

        service.saveSlotRequired(java.util.Arrays.asList(1L, null), List.of(), OPERATOR);

        assertThat(day.getRequiredSkills()).containsExactly(a);
    }
}
