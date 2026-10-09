package io.twinpilot.api.security;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** SecurityContext에서 요청 주체와 (계층을 반영한) 실효 역할을 꺼낸다. */
@Component
public class CurrentUser {

    private final RoleHierarchy hierarchy;

    public CurrentUser(RoleHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public Actor get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return new Actor("anonymous", "anonymous", Set.of(), false);
        }
        Set<String> roles = effectiveRoles(auth.getAuthorities());
        if (auth instanceof JwtAuthenticationToken jwt) {
            var token = jwt.getToken();
            String username = token.getClaimAsString("preferred_username");
            String clientId = token.getClaimAsString("azp");
            // 클라이언트 자격 증명으로 받은 서비스 계정 토큰: 사용자 이름이 없거나 service-account- 접두어
            boolean service = username == null || username.startsWith("service-account-");
            return new Actor(token.getSubject(), username != null ? username : clientId, roles, service);
        }
        return new Actor(auth.getName(), auth.getName(), roles, false);
    }

    public Set<String> effectiveRoles(Collection<? extends GrantedAuthority> authorities) {
        Set<String> roles = new TreeSet<>();
        for (GrantedAuthority a : hierarchy.getReachableGrantedAuthorities(authorities)) {
            String name = a.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                roles.add(name.substring(5));
            }
        }
        return roles;
    }

    /** URL 규칙으로 표현하기 어려운 세부 권한(예: Job 유형별 역할)을 확인한다. */
    public Actor require(Role role) {
        Actor actor = get();
        if (!actor.has(role)) {
            throw new AccessDeniedException(role.name() + " 역할이 필요합니다.");
        }
        return actor;
    }
}
