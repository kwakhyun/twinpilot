package io.twinpilot.api.job;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.security.Actor;
import io.twinpilot.api.security.Role;

/** Job 유형별 실행기. 빈으로 등록하면 작업 노드가 유형 이름으로 찾아 실행한다. */
public interface JobHandler {

    String type();

    /** 이 유형의 Job을 만들 수 있는 최소 역할. */
    Role requiredRole();

    /** 사용자별 동시 Job 한도에 포함할지(업로드 후처리처럼 사용자가 직접 요청하지 않은 Job은 제외). */
    default boolean countsTowardQuota() {
        return true;
    }

    /**
     * @param params Job 생성 시 저장한 파라미터(JSON)
     * @param actor  Job을 만든 사용자
     * @return JSON으로 직렬화할 결과
     */
    Object run(JsonNode params, Actor actor, Progress progress) throws Exception;
}
