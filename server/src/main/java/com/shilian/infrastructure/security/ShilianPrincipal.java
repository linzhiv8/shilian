package com.shilian.infrastructure.security;

import com.shilian.domain.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证成功后放进 SecurityContext 的主体。
 *
 * <p><b>为什么非得自己写一个，不能直接用 Spring 的 {@code User}。</b>
 * Spring 的 {@code User} 只带 username，而隔离数据用的是 {@code user.id}——
 * 用户名是可以改的，id 不能。如果靠 username 反查 id，
 * 那么「改用户名」这个功能就变成了「改一次要顺带修一遍所有关联」的隐患。
 * 直接把 id 带在主体上，一次查库、全程可用。
 *
 * <p>继承而不是实现 {@link UserDetails}：父类那套
 * enabled / accountNonLocked 的判断已经写好了，没必要再抄一遍。
 */
public final class ShilianPrincipal extends org.springframework.security.core.userdetails.User {

    private final String userId;
    private final String displayName;

    public ShilianPrincipal(User u, LocalDateTime now) {
        super(u.username(),
                u.passwordHash() == null ? "" : u.passwordHash(),
                !u.isDisabled(),
                true,   // accountNonExpired
                true,   // credentialsNonExpired
                !u.isLockedAt(now),
                List.<GrantedAuthority>of());
        this.userId = u.id();
        this.displayName = u.displayName();
    }

    /** 数据隔离用的主键。 */
    public String userId() {
        return userId;
    }

    /** 界面上显示的名字。 */
    public String displayName() {
        return displayName;
    }
}
