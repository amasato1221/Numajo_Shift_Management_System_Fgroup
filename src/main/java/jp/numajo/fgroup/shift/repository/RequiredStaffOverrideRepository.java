package jp.numajo.fgroup.shift.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.RequiredStaffOverride;
import jp.numajo.fgroup.shift.entity.id.RequiredStaffOverrideId;

/**
 * 必要人数（日付ごとの上書き）のリポジトリ。
 */
public interface RequiredStaffOverrideRepository
        extends JpaRepository<RequiredStaffOverride, RequiredStaffOverrideId> {

    /** 日付の範囲で取得する。 */
    List<RequiredStaffOverride> findByIdWorkDateBetween(LocalDate from, LocalDate to);
}
