# Spring Boot 4.1 layered-jar image (docs/acceptance-criteria/system.md, decision S1).
# Build the jar first: ./gradlew bootJar
# Base image pinned by its index digest (amd64 + arm64), read 2026-11-23 with
# `docker buildx imagetools inspect eclipse-temurin:21-jre`.
FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d AS builder
WORKDIR /builder
COPY build/libs/app.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
WORKDIR /application
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
# Heap 55% of the container limit, bounded direct memory, exit cleanly on OOM, no heap dump (decision S2).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# Exec form, so the JVM receives SIGTERM.
ENTRYPOINT ["java", "-jar", "application.jar"]
