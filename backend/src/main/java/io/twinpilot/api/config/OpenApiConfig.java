package io.twinpilot.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 문서에 OIDC(Authorization Code + PKCE)와 Bearer 인증 방식을 싣는다. Swagger UI의 Authorize 버튼이 이를 쓴다. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI twinPilotOpenApi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        String oidc = issuer + "/protocol/openid-connect";
        return new OpenAPI()
                .info(new Info().title("TwinPilot API").version("v1")
                        .description("디지털트윈 안전 분석 API. 역할: VIEWER < ANALYST < PLANNER < ADMIN, INGEST(센서 수집)"))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme().type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(oidc + "/auth").tokenUrl(oidc + "/token"))))
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("oidc"))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
