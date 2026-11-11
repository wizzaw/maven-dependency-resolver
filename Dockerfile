# Stage 1: Build self-contained shaded JAR
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal Alpine JRE 21 runtime (~180MB image, <100MB idle RAM)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user for container security
RUN addgroup -S bundler && adduser -S bundler -G bundler \
    && mkdir -p /tmp/maven-airgap-bundler-cache \
    && chown -R bundler:bundler /app /tmp/maven-airgap-bundler-cache

COPY --from=builder /build/target/maven-airgap-bundler.jar /app/app.jar

USER bundler
EXPOSE 8080

ENV PORT=8080 \
    LOCAL_POM_CACHE_DIR=/tmp/maven-airgap-bundler-cache \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.awt.headless=true"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
