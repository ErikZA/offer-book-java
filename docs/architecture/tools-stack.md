# 🛠️ Stack de Tecnologia: MVP Livro de Ofertas

## 1. Linguagem & Framework Base

* **Java 21:** Escolha estratégica para aproveitar as *Virtual Threads* (Projeto Loom), permitindo altíssima concorrência com baixo consumo de recursos.
* **Spring Boot 3.x:** Base robusta e moderna para a construção dos microsserviços.

## 2. Bibliotecas Externas (Libs & Ferramentas)

* **Lombok:** Redução de código *boilerplate* (Getters, Setters, Builders, etc.).
* **MapStruct:** Mapeamento em tempo de compilação entre Entidades e DTOs, garantindo alta performance na conversão de dados.
* **Flyway / Liquibase:** Versionamento e controle automatizado de migrações estruturais no PostgreSQL.
* **Mongock:** Gerenciamento de migrações e versionamento de dados (documentos) no MongoDB.
* **Springdoc OpenAPI (Swagger):** Documentação interativa, viva e padronizada dos contratos das APIs REST.
* **Resilience4j:** Implementação de padrões de tolerância a falhas para integrações seguras, incluindo *Circuit Breaker*, *Retries* e *Rate Limiting*.
* **Spring Retry (`spring-retry` + `spring-boot-starter-aop`):** Retentativas declarativas via `@Retryable` com *backoff* exponencial. Usado pelo `OutboxPublisherService` para garantir at-least-once na entrega de eventos ao RabbitMQ — até 5 tentativas com delay inicial de 500 ms dobrando a cada falha (máx. 10 s).
* **Micrometer Tracing (OpenTelemetry):** Rastreabilidade distribuída (Trace ID/Span ID) para acompanhar o fluxo e gargalos das requisições entre os microsserviços.
* **Nimbus JOSE + JWT:** Validação robusta de tokens de segurança e integração nativa com o JWKS do Keycloak.

## 3. Arquitetura & Padrões

* **Padrões Técnicos:** Arquitetura de Microsserviços, *Event-Driven* (Orientada a Eventos), CQRS (Segregação de Comandos e Consultas) e interfaces RESTful.
* **Resiliência e Integridade:** Foco em Consistência Eventual, Idempotência no consumo de mensagens e uso do *Transactional Outbox Pattern* para evitar perda de eventos entre o banco e a mensageria.
* **CDC (Change Data Capture):** O relay do Outbox para o RabbitMQ é feito via **Polling com `SELECT FOR UPDATE SKIP LOCKED`**, permitindo escalabilidade horizontal (N instâncias concorrentes sem duplicatas). Sem dependência de replication slots ou WAL lógico (ver [ddd-cqrs-event-source.md](ddd-cqrs-event-source.md)).

## 4. Infraestrutura, Dados & Serviços

* **Bancos de Dados:** PostgreSQL (garantia transacional ACID para saldos financeiros e *Outbox*) e MongoDB (armazenamento de alta performance para o histórico e estado documental das ordens).
* **Cache & Motor (Match):** Redis atuando como o "cérebro" in-memory do *Order Book*, utilizando *Sorted Sets* para ordenação ultrarrápida de preços e tempo. Todos os containers Redis estão protegidos com `requirepass` (AT-04) — senhas via env vars `REDIS_PASSWORD` (app) e `REDIS_KONG_PASSWORD` (Kong rate-limiting).
* **Mensageria:** RabbitMQ para roteamento e enfileiramento de eventos assíncronos (ex: fila de liquidação de trades).
* **Gateway & Auth:** Kong atuando como API Gateway (ponto de entrada único e controle de tráfego) e Keycloak como *Identity Provider* (IAM) para gestão centralizada de usuários e autenticação.
* **JWKS Rotator Sidecar (AT-13.1):** Container Alpine (`jwks-rotator`) que verifica a cada 6 horas se o `kid` da chave RSA do Keycloak mudou e atualiza a credencial JWT do consumer no Kong via Admin API — sem reinicialização do Kong, sem downtime. Script `jwks-rotation.sh` idempotente (no-op se `kid` não mudou); log auditável em NDJSON. Equivalente a um CronJob `0 */6 * * *` em Kubernetes.
* **DevOps:** Docker e Docker Compose para orquestração idêntica de ambientes de desenvolvimento e *staging*, incluindo limites configurados de CPU e RAM para simular cenários reais de estresse.

## 5. Observabilidade & Métricas

* **Jaeger (AT-14.1):** Distributed tracing via OpenTelemetry (OTLP HTTP). Visualização de traces end-to-end da Saga no Jaeger UI (`localhost:16686`).
* **Micrometer Registry Prometheus (AT-15.2):** Exportação de métricas de negócio (`vibranium.*`) e JVM via `/actuator/prometheus`.
* **Prometheus (AT-12):** Coleta de métricas dos microsserviços via scrape HTTP a cada 15 segundos. Targets: `order-service:8080`, `wallet-service:8081`.
* **Grafana (AT-12):** Dashboards provisionados automaticamente via volumes Docker — 4 dashboards (Order Flow, Wallet Health, Infrastructure, SLA) + 3 alertas críticos (outbox depth, error rate, circuit breaker). Acesso: `localhost:3000`.

## 6. Testes & Qualidade de Código

* **Testcontainers:** Automação da subida de instâncias reais e efêmeras (PostgreSQL, MongoDB, Redis e RabbitMQ) via Docker para testes de integração 100% confiáveis. O container PostgreSQL dos testes não requer mais `wal_level=logical` pois o relay do Outbox agora usa Polling com SKIP LOCKED.
* **ArchUnit:** Testes automatizados de arquitetura para garantir, via CI/CD, que as regras do CQRS, Clean Architecture e isolamento de módulos não sejam violadas pela equipe.
* **JUnit 5, Mockito e REST Assured:** Tríade fundamental para testes unitários, simulação (mocks) de comportamentos e validação de contratos de API *end-to-end*.
