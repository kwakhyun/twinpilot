package io.twinpilot.api.security;

import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.config.TwinPilotProperties;

/**
 * 인증·인가.
 * - oidc(기본): Keycloak 등 OIDC IdP가 발급한 JWT 액세스 토큰을 검증한다(서명·만료·발급자·대상(aud)).
 * - disabled: 로컬 개발 전용. 모든 요청을 ADMIN 개발자로 처리하고 기동 로그에 경고를 남긴다.
 * 권한 규칙은 아래 한곳에 모아 검토하기 쉽게 한다. 규칙에 없는 경로는 거부한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    RoleHierarchy roleHierarchy() {
        return Role.hierarchy();
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, TwinPilotProperties props, ObjectMapper mapper) throws Exception {
        var security = props.security();
        var problems = new ProblemAuthHandlers(mapper);
        http.csrf(c -> c.disable())   // 쿠키 세션 없이 Bearer 토큰만 쓰므로 CSRF 대상이 아니다.
                .cors(c -> { })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a
                        // 이미 인가된 요청의 비동기(SSE) 재디스패치와 오류 페이지는 다시 막지 않는다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 관리 포트(8081)로 분리된 Actuator. Ingress에는 노출하지 않는다.
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/sensors/readings").hasRole(Role.INGEST.name())
                        .requestMatchers("/api/v1/admin/**").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/facilities").hasRole(Role.PLANNER.name())
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/facilities/**").hasRole(Role.PLANNER.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/models").hasRole(Role.PLANNER.name())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/models/**").hasRole(Role.PLANNER.name())
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/models/**").hasRole(Role.PLANNER.name())
                        // Job 유형별 세부 역할(위험도 재계산·적재는 ADMIN)은 JobService에서 확인한다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/analysis/jobs").hasRole(Role.ANALYST.name())
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/analysis/jobs/**").hasRole(Role.ANALYST.name())
                        .requestMatchers(HttpMethod.POST, "/api/v1/analysis/coverage", "/api/v1/analysis/response")
                        .hasRole(Role.VIEWER.name())
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasRole(Role.VIEWER.name())
                        .anyRequest().denyAll());

        if (security.disabled()) {
            log.warn("보안 비활성화(twinpilot.security.mode=disabled): 모든 요청을 ADMIN 개발자로 처리합니다. 운영에서 사용하지 마세요.");
            http.addFilterBefore(new DevUserFilter(), AnonymousAuthenticationFilter.class);
        } else {
            http.oauth2ResourceServer(o -> o
                    .authenticationEntryPoint(problems)
                    .accessDeniedHandler(problems)
                    .jwt(j -> j.jwtAuthenticationConverter(new JwtRoleConverter(security.roleClaims()))));
        }
        return http.build();
    }

    /** 보안 비활성 모드에서 모든 요청에 개발자 주체를 넣는다. */
    static final class DevUserFilter extends OncePerRequestFilter {

        private static final UsernamePasswordAuthenticationToken DEV = UsernamePasswordAuthenticationToken.authenticated(
                "dev", null, List.of(new SimpleGrantedAuthority(Role.ADMIN.authority()),
                        new SimpleGrantedAuthority(Role.INGEST.authority())));

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, java.io.IOException {
            SecurityContextHolder.getContext().setAuthentication(DEV);
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
