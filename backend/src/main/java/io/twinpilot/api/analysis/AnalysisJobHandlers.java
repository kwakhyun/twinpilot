package io.twinpilot.api.analysis;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.analysis.AnalysisDtos.JobRequest;
import io.twinpilot.api.job.JobHandler;
import io.twinpilot.api.job.Progress;
import io.twinpilot.api.security.Actor;
import io.twinpilot.api.security.Role;

/** 분석 Job 유형 등록. */
@Configuration
public class AnalysisJobHandlers {

    public static final String RECOMMEND_SITES = "RECOMMEND_SITES";
    public static final String REFRESH_RISK = "REFRESH_RISK";

    @Bean
    JobHandler recommendSitesHandler(RecommendationService recommendation) {
        return new JobHandler() {
            @Override
            public String type() {
                return RECOMMEND_SITES;
            }

            @Override
            public Role requiredRole() {
                return Role.ANALYST;
            }

            @Override
            public Object run(JsonNode p, Actor actor, Progress progress) {
                JobRequest req = new JobRequest(RECOMMEND_SITES, text(p, "facilityType"), integer(p, "count"),
                        p.path("radiusM").isNumber() ? p.path("radiusM").asDouble() : null, integer(p, "regionId"),
                        integer(p, "cellSizeM"));
                return recommendation.recommend(req, progress);
            }
        };
    }

    @Bean
    JobHandler refreshRiskHandler(RiskService risk) {
        return new JobHandler() {
            @Override
            public String type() {
                return REFRESH_RISK;
            }

            @Override
            public Role requiredRole() {
                return Role.ADMIN;
            }

            @Override
            public Object run(JsonNode p, Actor actor, Progress progress) {
                return Map.of("updatedBuildings", risk.refresh(progress));
            }
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asInt() : null;
    }
}
