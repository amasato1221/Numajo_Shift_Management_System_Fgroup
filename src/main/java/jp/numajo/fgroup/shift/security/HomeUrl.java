package jp.numajo.fgroup.shift.security;

import jp.numajo.fgroup.shift.constant.Role;

/**
 * 利用者の種類ごとのホーム画面のURL。
 */
public final class HomeUrl {

    private HomeUrl() {
    }

    public static String of(Role role) {
        return switch (role) {
            case ADMIN -> "/admin/home";
            case MANAGER -> "/manager/home";
            case STAFF -> "/staff/home";
        };
    }
}
