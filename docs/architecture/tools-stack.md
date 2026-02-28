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
* **Debezium Embedded (`debezium-api`, `debezium-embedded`, `debezium-connector-postgres` 2.7.4.Final):** Change Data Capture (CDC) sobre o WAL do PostgreSQL. Captura INSERTs na tabela `outbox_message` em tempo real sem polling periódico. Elimina a janela de inconsistência entre a escrita transacional no banco e a publicação no RabbitMQ. Requer `wal_level=logical`, `max_replication_slots ≥ nº_de_instâncias` e `max_wal_senders ≥ nº_de_instâncias` no PostgreSQL.
* **Micrometer Tracing (OpenTelemetry):** Rastreabilidade distribuída (Trace ID/Span ID) para acompanhar o fluxo e gargalos das requisições entre os microsserviços.
* **Nimbus JOSE + JWT:** Validação robusta de tokens de segurança e integração nativa com o JWKS do Keycloak.

## 3. Arquitetura & Padrões

* **Padrões Técnicos:** Arquitetura de Microsserviços, *Event-Driven* (Orientada a Eventos), CQRS (Segregação de Comandos e Consultas) e interfaces RESTful.
* **Resiliência e Integridade:** Foco em Consistência Eventual, Idempotência no consumo de mensagens e uso do *Transactional Outbox Pattern* para evitar perda de eventos entre o banco e a mensageria.
* **CDC (Change Data Capture):** O relay do Outbox para o RabbitMQ é feito via Debezium Embedded assistindo diretamente o WAL do PostgreSQL — sem polling e sem risco de dupla escrita em sistemas distribuídos (ver [ddd-cqrs-event-source.md](ddd-cqrs-event-source.md)).

## 4. Infraestrutura, Dados & Serviços

* **Bancos de Dados:** PostgreSQL (garantia transacional ACID para saldos financeiros e *Outbox*) e MongoDB (armazenamento de alta performance para o histórico e estado documental das ordens).
* **Cache & Motor (Match):** Redis atuando como o "cérebro" in-memory do *Order Book*, utilizando *Sorted Sets* para ordenação ultrarrápida de preços e tempo.
* **Mensageria:** RabbitMQ para roteamento e enfileiramento de eventos assíncronos (ex: fila de liquidação de trades).
* **Gateway & Auth:** Kong atuando como API Gateway (ponto de entrada único e controle de tráfego) e Keycloak como *Identity Provider* (IAM) para gestão centralizada de usuários e autenticação.
* **DevOps:** Docker e Docker Compose para orquestração idêntica de ambientes de desenvolvimento e *staging*, incluindo limites configurados de CPU e RAM para simular cenários reais de estresse.

## 5. Testes & Qualidade de Código

* **Testcontainers:** Automação da subida de instâncias reais e efêmeras (PostgreSQL, MongoDB, Redis e RabbitMQ) via Docker para testes de integração 100% confiáveis. O container PostgreSQL dos testes é inicializado com `wal_level=logical`, `max_replication_slots=10` e `max_wal_senders=10` para viabilizar o CDC do Debezium nos testes de integração do Outbox Publisher.
* **ArchUnit:** Testes automatizados de arquitetura para garantir, via CI/CD, que as regras do CQRS, Clean Architecture e isolamento de módulos não sejam violadas pela equipe.
* **JUnit 5, Mockito e REST Assured:** Tríade fundamental para testes unitários, simulação (mocks) de comportamentos e validação de contratos de API *end-to-end*.
