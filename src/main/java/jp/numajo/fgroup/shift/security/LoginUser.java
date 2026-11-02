package jp.numajo.fgroup.shift.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import jp.numajo.fgroup.shift.constant.Role;
import jp.numajo.fgroup.shift.entity.User;
import lombok.Getter;

/**
 * ログイン中の利用者の情報。セッションに保存されるため、エンティティそのものは持たない。
 */
@Getter
public class LoginUser implements UserDetails {

    private final Long userId;
    private final String loginId;
    private final String passwordHash;
    private final String name;
    private final Role role;
    private final boolean active;
    /** 読み込んだ時点でロック中だったか（LoginUserDetailsService が Clock で判定する） */
    private final boolean locked;
    private boolean mustChangePassword;

    public LoginUser(User user, boolean locked) {
        this.userId = user.getId();
        this.loginId = user.getLoginId();
        this.passwordHash = user.getPasswordHash();
        this.name = user.getName();
        this.role = user.getRole();
        this.active = user.isActive();
        this.locked = locked;
        this.mustChangePassword = user.isMustChangePassword();
    }

    /** パスワード変更後に、変更が必要な状態を解除する。 */
    public void passwordChanged() {
        this.mustChangePassword = false;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
