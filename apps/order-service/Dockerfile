# Multi-stage build para Order Service

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy pom files
COPY pom.xml .
COPY libs/ libs/
COPY apps/order-service apps/order-service

# Build
RUN mvn clean package -DskipTests -pl apps/order-service

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy JAR do stage anterior
COPY --from=builder /app/apps/order-service/target/order-service-1.0.0-SNAPSHOT.jar app.jar

# JVM tuning para container
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
