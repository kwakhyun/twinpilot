package io.twinpilot.api.security;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

/**
 * 역할. 상위 역할은 하위 역할의 권한을 모두 가진다(ADMIN > PLANNER > ANALYST > VIEWER).
 * INGEST는 센서 게이트웨이(서비스 계정)용으로 계층과 분리한다.
 */
public enum Role {
    /** 지도·건물·통계·센서 조회, 커버리지 분석, 코파일럿 대화 */
    VIEWER,
    /** 최적입지 추천 등 계산 비용이 큰 분석 Job 실행 */
    ANALYST,
    /** 계획 시설 배치·삭제, 3D 모델 업로드·배치 */
    PLANNER,
    /** 데이터 적재, 위험도·도로망 재계산, 감사 로그 */
    ADMIN,
    /** 센서 측정값 수집 API */
    INGEST;

    public String authority() {
        return "ROLE_" + name();
    }

    public static RoleHierarchy hierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(ADMIN.name()).implies(PLANNER.name())
                .role(PLANNER.name()).implies(ANALYST.name())
                .role(ANALYST.name()).implies(VIEWER.name())
                .build();
    }
}
