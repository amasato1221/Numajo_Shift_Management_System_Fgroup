package jp.numajo.fgroup.shift.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.RequiredStaffDefault;
import jp.numajo.fgroup.shift.entity.id.RequiredStaffDefaultId;

/**
 * 必要人数（曜日の既定値）のリポジトリ。findAll() で14行を取る。
 */
public interface RequiredStaffDefaultRepository extends JpaRepository<RequiredStaffDefault, RequiredStaffDefaultId> {
}
