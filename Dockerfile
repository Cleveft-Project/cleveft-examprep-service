# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S cleveft && adduser -S cleveft -G cleveft
COPY --from=build /build/target/*.jar app.jar
USER cleveft

EXPOSE 8085
# This service calls transcription and query by name; see the gateway
# Dockerfile for why the JVM DNS cache has to be kept short.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dsun.net.inetaddr.ttl=5 -Dsun.net.inetaddr.negative.ttl=0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
