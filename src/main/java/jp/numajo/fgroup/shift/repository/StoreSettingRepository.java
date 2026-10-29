package jp.numajo.fgroup.shift.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import jp.numajo.fgroup.shift.entity.StoreSetting;

/**
 * 店舗設定（store_settings）のリポジトリ。行は id=1 の1行だけ。
 */
public interface StoreSettingRepository extends JpaRepository<StoreSetting, Byte> {
}
