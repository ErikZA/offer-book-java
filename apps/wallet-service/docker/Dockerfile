# Multi-stage build para Wallet Service

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy pom files
COPY pom.xml .
COPY libs/ libs/
COPY apps/wallet-service apps/wallet-service
COPY apps/order-service apps/order-service

# Build
RUN mvn clean package -DskipTests -am -pl apps/wallet-service

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Criar grupo e usuário não-root para executar a aplicação com privilégios mínimos
# Reduz a superfície de ataque em caso de vulnerabilidade na JVM ou na aplicação
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy JAR do stage anterior (executado como root para garantir permissões de escrita)
COPY --from=builder /app/apps/wallet-service/target/wallet-service-1.0.0-SNAPSHOT.jar app.jar

# JVM tuning para container
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication"

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8081/actuator/health || exit 1

# Trocar para usuário não-root antes de iniciar o processo
USER appuser

# Shell form necessário para expandir $JAVA_OPTS — exec form não interpreta variáveis de ambiente
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
