# syntax=docker/dockerfile:1.7
# twin-api: Spring Boot 4 + Java 21 (계층형 jar로 이미지 캐시 효율화)

# 의존성 캐시 주입용 빈 스테이지. CI·로컬에서 미리 받은 Maven 저장소를 넣을 수 있다:
#   docker build --build-context m2repo=$HOME/.m2/repository ...
FROM scratch AS m2repo

FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY --from=m2repo . /root/.m2/repository/
COPY pom.xml .
COPY src src
# 의존성을 주입했다면 오프라인 빌드도 가능하다: --build-arg MAVEN_ARGS="-B -q -o"
ARG MAVEN_ARGS="-B -q"
RUN mvn ${MAVEN_ARGS} package -DskipTests \
 && java -Djarmode=tools -jar target/twin-api-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
# 베이스 이미지의 pebble(서비스 관리자, Go 바이너리)은 JRE 실행에 쓰지 않으므로 지운다(취약점 검사 대상 축소).
RUN groupadd -r app && useradd -r -g app -u 10001 app && mkdir -p /data/storage && chown app:app /data/storage \
 && rm -f /usr/bin/pebble
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
ENV STORAGE_LOCAL_ROOT=/data/storage LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
