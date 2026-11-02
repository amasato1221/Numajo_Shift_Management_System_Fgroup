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
 * 判定はすべて条件が1つずつなので、組み合わせ表は要らない（条件網羅＝判定条件網羅）。
 * Repository と OperationLogService はモックにする。
 * </p>
 *
 * <pre>
 * メソッド           | 判定                          | 真のとき          | テスト
 * create             | existsByName                  | E2404            | 同じ名前で追加
 * update             | findById が空                  | ResourceNotFound | 変更で見つからない
 * update             | existsByNameAndIdNot          | E2404            | 変更で同じ名前
 * delete             | findById が空                  | ResourceNotFound | 削除で見つからない
 * saveSlotRequired   | 取れた職能の数 != 選んだ数      | E2405            | 存在しない職能ID
 * saveSlotRequired   | 枠のID が 日勤／夕勤／それ以外   | 置き換え／無視    | 保存
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
    void findAllは名前順の全件を返す() {
        List<Skill> skills = List.of(skill(1L, "レジ", null));
        when(skillRepository.findAllByOrderByNameAscIdAsc()).thenReturn(skills);

        assertThat(service.findAll()).isSameAs(skills);
    }

    @Test
    void findRowsは持っている人数を付け_いない職能は0人() {
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
    void getSlotSkillFormは日勤と夕勤の必要職能を職能ID順で返す() {
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
    void getSlotSkillFormは必要職能がなければ空のリスト() {
        when(slotRepository.findAllWithRequiredSkills())
                .thenReturn(List.of(slot(SlotIds.DAY), slot(SlotIds.EVENING)));

        SlotSkillForm form = service.getSlotSkillForm();

        assertThat(form.getDaySkillIds()).isEmpty();
        assertThat(form.getEveningSkillIds()).isEmpty();
    }

    // ---------------------------------------------------------------- 追加

    @Test
    void 同じ名前で追加するとE2404で保存もログも残さない() {
        when(skillRepository.existsByName("レジ")).thenReturn(true);

        assertThatThrownBy(() -> service.create("レジ", null, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting("messageCode").isEqualTo("E2404");

        verify(skillRepository, never()).save(any());
        verifyNoInteractions(operationLogService);
    }

    @Test
    void 追加すると1行保存して操作ログSKILL_CREATEを残す() {
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
    void 変更で職能が見つからなければResourceNotFoundException() {
        when(skillRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(5L, "新", null, OPERATOR))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(operationLogService);
    }

    @Test
    void 変更でほかの職能と同じ名前ならE2404で上書きしない() {
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
    void 変更すると名前と説明を上書きして操作ログSKILL_UPDATEに古い名前と新しい名前を残す() {
        Skill target = skill(5L, "旧", "説明");
        when(skillRepository.findById(5L)).thenReturn(Optional.of(target));
        when(skillRepository.existsByNameAndIdNot("新", 5L)).thenReturn(false);

        service.update(5L, "新", "新しい説明", OPERATOR);

        assertThat(target.getName()).isEqualTo("新");
        assertThat(target.getDescription()).isEqualTo("新しい説明");
        verify(operationLogService).record(OPERATOR, OperationAction.SKILL_UPDATE, "skill:5", "旧 → 新");
    }

    @Test
    void 自分と同じ名前のまま説明だけ変えられる() {
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
    void 削除で職能が見つからなければResourceNotFoundException() {
        when(skillRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(5L, OPERATOR))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(skillRepository, never()).delete(any());
        verifyNoInteractions(operationLogService);
    }

    @Test
    void 削除すると職能を消して操作ログSKILL_DELETEに名前を残す() {
        Skill target = skill(5L, "レジ", null);
        when(skillRepository.findById(5L)).thenReturn(Optional.of(target));

        service.delete(5L, OPERATOR);

        verify(skillRepository).delete(target);
        verify(operationLogService).record(OPERATOR, OperationAction.SKILL_DELETE, "skill:5", "レジ");
    }

    // ---------------------------------------------------------------- 枠の必要職能

    @Test
    void 存在しない職能IDがあればE2405で枠も変えずログも残さない() {
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
    void 保存すると日勤と夕勤の必要職能をそれぞれ置き換えて操作ログを残す() {
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
    void nullは空のリストとして扱い全部外せる() {
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
    void 同じ職能IDが重なっていても1つとして扱う() {
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
    void 日勤と夕勤以外の枠は読み取りも置き換えもしない() {
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
    void リストの中のnullは無視する() {
        Skill a = skill(1L, "A", null);
        Slot day = slot(SlotIds.DAY);
        when(skillRepository.findAllById(any())).thenReturn(List.of(a));
        when(slotRepository.findAllWithRequiredSkills()).thenReturn(List.of(day));

        service.saveSlotRequired(java.util.Arrays.asList(1L, null), List.of(), OPERATOR);

        assertThat(day.getRequiredSkills()).containsExactly(a);
    }
}
