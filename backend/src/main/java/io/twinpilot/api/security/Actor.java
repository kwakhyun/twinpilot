package io.twinpilot.api.security;

import java.util.Set;

/** 요청 주체. 감사 로그·Job 소유자·사용자별 한도에 쓴다. */
public record Actor(String subject, String username, Set<String> roles, boolean service) {

    public static final Actor SYSTEM = new Actor("system", "system", Set.of(Role.ADMIN.name()), true);

    public boolean has(Role role) {
        return roles.contains(role.name());
    }
}
