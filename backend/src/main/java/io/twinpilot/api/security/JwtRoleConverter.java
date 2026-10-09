package io.twinpilot.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 토큰의 역할 클레임을 ROLE_* 권한으로 바꾼다.
 * 기본 경로는 Keycloak 형식(realm_access.roles)이며, 다른 IdP를 위해 점(.)으로 구분한 경로를 여러 개 받는다.
 * 알 수 없는 역할 이름은 버린다(IdP의 기본 역할이 권한으로 새어 들어오지 않게).
 */
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final List<String> claimPaths;

    public JwtRoleConverter(List<String> claimPaths) {
        this.claimPaths = claimPaths;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String path : claimPaths) {
            for (String role : stringsAt(jwt.getClaims(), path)) {
                String normalized = role.toUpperCase();
                if (isKnown(normalized)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + normalized));
                }
            }
        }
        String name = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
    }

    private static boolean isKnown(String role) {
        for (Role r : Role.values()) {
            if (r.name().equals(role)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static Collection<String> stringsAt(Map<String, Object> claims, String path) {
        Object node = claims;
        for (String key : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return List.of();
            }
            node = ((Map<String, Object>) map).get(key);
        }
        if (node instanceof Collection<?> values) {
            List<String> out = new ArrayList<>();
            values.forEach(v -> out.add(String.valueOf(v)));
            return out;
        }
        if (node instanceof String s) {
            return List.of(s.split("[ ,]+"));
        }
        return List.of();
    }
}
