package jp.numajo.fgroup.shift.entity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import jp.numajo.fgroup.shift.constant.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ユーザー（users）のエンティティ。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    /** 主キー（自動採番） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** ログインID */
    @Column(name = "login_id")
    private String loginId;

    /** BCryptハッシュ */
    @Column(name = "password_hash")
    private String passwordHash;

    /** 氏名 */
    @Column(name = "name")
    private String name;

    /** ふりがな */
    @Column(name = "name_kana")
    private String nameKana;

    /** 利用者の種類 */
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role;

    /** 研修中バッジ */
    @Column(name = "is_trainee")
    private boolean trainee;

    /** 有効フラグ */
    @Column(name = "is_active")
    private boolean active;

    /** 初回パスワード変更が必要か */
    @Column(name = "must_change_password")
    private boolean mustChangePassword;

    /** 連続ログイン失敗回数 */
    @Column(name = "failed_login_count")
    private int failedLoginCount;

    /** ロック解除日時 */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** 持っている職能 */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_skills",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> skills = new HashSet<>();

    /** 登録日時 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新日時 */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
