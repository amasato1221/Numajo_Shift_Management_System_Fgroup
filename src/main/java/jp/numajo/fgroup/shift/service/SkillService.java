package jp.numajo.fgroup.shift.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import lombok.RequiredArgsConstructor;

/**
 * 職能に関する業務（SM-04。30-05 3.3）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillService {

    private final SkillRepository skillRepository;
    private final SlotRepository slotRepository;
    private final OperationLogService operationLogService;

    /** 職能を名前順に全件取る。 */
    public List<Skill> findAll() {
        return skillRepository.findAllByOrderByNameAscIdAsc();
    }

    /** SM-04 職能の一覧（持っている人数つき。20-SM-04 2.1-1）。 */
    public List<SkillRowView> findRows() {
        Map<Long, Long> holderCounts = new HashMap<>();
        for (Object[] row : skillRepository.countActiveStaffPerSkill()) {
            holderCounts.put((Long) row[0], (Long) row[1]);
        }
        List<SkillRowView> rows = new ArrayList<>();
        for (Skill skill : skillRepository.findAllByOrderByNameAscIdAsc()) {
            rows.add(new SkillRowView(skill.getId(), skill.getName(), skill.getDescription(),
                    holderCounts.getOrDefault(skill.getId(), 0L)));
        }
        return rows;
    }

    /** SM-04 枠ごとの必要な職能の、今の値（20-SM-04 2.1-2）。 */
    public SlotSkillForm getSlotSkillForm() {
        SlotSkillForm form = new SlotSkillForm();
        for (Slot slot : slotRepository.findAllWithRequiredSkills()) {
            List<Long> skillIds = slot.getRequiredSkills().stream()
                    .map(Skill::getId)
                    .sorted()
                    .toList();
            if (slot.getId() == SlotIds.DAY) {
                form.setDaySkillIds(new ArrayList<>(skillIds));
            } else if (slot.getId() == SlotIds.EVENING) {
                form.setEveningSkillIds(new ArrayList<>(skillIds));
            }
        }
        return form;
    }

    /** SM-04 職能を追加する（20-SM-04 2.2）。 */
    @Transactional
    public void create(String name, String description, Long operatorId) {
        if (skillRepository.existsByName(name)) {
            throw new BusinessException("E2404");
        }
        Skill skill = new Skill();
        skill.setName(name);
        skill.setDescription(description);
        Skill saved = skillRepository.save(skill);
        operationLogService.record(operatorId, OperationAction.SKILL_CREATE, "skill:" + saved.getId(), name);
    }

    /** SM-04 職能の名前・説明を変える（20-SM-04 2.3）。 */
    @Transactional
    public void update(Long id, String name, String description, Long operatorId) {
        Skill skill = findSkill(id);
        if (skillRepository.existsByNameAndIdNot(name, id)) {
            throw new BusinessException("E2404");
        }
        String oldName = skill.getName();
        skill.setName(name);
        skill.setDescription(description);
        operationLogService.record(operatorId, OperationAction.SKILL_UPDATE, "skill:" + id,
                oldName + " → " + name);
    }

    /** SM-04 職能を削除する（20-SM-04 2.4）。持っている人・枠の行は外部キーの CASCADE で消える。 */
    @Transactional
    public void delete(Long id, Long operatorId) {
        Skill skill = findSkill(id);
        String name = skill.getName();
        skillRepository.delete(skill);
        operationLogService.record(operatorId, OperationAction.SKILL_DELETE, "skill:" + id, name);
    }

    /** SM-04 枠ごとの必要な職能を保存する（20-SM-04 2.5）。 */
    @Transactional
    public void saveSlotRequired(List<Long> daySkillIds, List<Long> eveningSkillIds, Long operatorId) {
        List<Long> dayIds = distinct(daySkillIds);
        List<Long> eveningIds = distinct(eveningSkillIds);

        Set<Long> allIds = new HashSet<>(dayIds);
        allIds.addAll(eveningIds);
        Map<Long, Skill> skills = new HashMap<>();
        for (Skill skill : skillRepository.findAllById(allIds)) {
            skills.put(skill.getId(), skill);
        }
        if (skills.size() != allIds.size()) {
            throw new BusinessException("E2405");
        }

        for (Slot slot : slotRepository.findAllWithRequiredSkills()) {
            List<Long> ids;
            if (slot.getId() == SlotIds.DAY) {
                ids = dayIds;
            } else if (slot.getId() == SlotIds.EVENING) {
                ids = eveningIds;
            } else {
                continue;
            }
            slot.getRequiredSkills().clear();
            for (Long skillId : ids) {
                slot.getRequiredSkills().add(skills.get(skillId));
            }
        }
        operationLogService.record(operatorId, OperationAction.SLOT_SKILL_UPDATE, null,
                "DAY=" + dayIds + ", EVENING=" + eveningIds);
    }

    private Skill findSkill(Long id) {
        return skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("skill id=" + id));
    }

    /** null は空のリストにし、重複とnullを除く（順番は変えない）。 */
    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return new ArrayList<>();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                set.add(id);
            }
        }
        return new ArrayList<>(set);
    }
}
