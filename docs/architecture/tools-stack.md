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
* **Micrometer Tracing (OpenTelemetry):** Rastreabilidade distribuída (Trace ID/Span ID) para acompanhar o fluxo e gargalos das requisições entre os microsserviços.
* **Nimbus JOSE + JWT:** Validação robusta de tokens de segurança e integração nativa com o JWKS do Keycloak.

## 3. Arquitetura & Padrões

* **Padrões Técnicos:** Arquitetura de Microsserviços, *Event-Driven* (Orientada a Eventos), CQRS (Segregação de Comandos e Consultas) e interfaces RESTful.
* **Resiliência e Integridade:** Foco em Consistência Eventual, Idempotência no consumo de mensagens e uso do *Transactional Outbox Pattern* para evitar perda de eventos entre o banco e a mensageria.

## 4. Infraestrutura, Dados & Serviços

* **Bancos de Dados:** PostgreSQL (garantia transacional ACID para saldos financeiros e *Outbox*) e MongoDB (armazenamento de alta performance para o histórico e estado documental das ordens).
* **Cache & Motor (Match):** Redis atuando como o "cérebro" in-memory do *Order Book*, utilizando *Sorted Sets* para ordenação ultrarrápida de preços e tempo.
* **Mensageria:** RabbitMQ para roteamento e enfileiramento de eventos assíncronos (ex: fila de liquidação de trades).
* **Gateway & Auth:** Kong atuando como API Gateway (ponto de entrada único e controle de tráfego) e Keycloak como *Identity Provider* (IAM) para gestão centralizada de usuários e autenticação.
* **DevOps:** Docker e Docker Compose para orquestração idêntica de ambientes de desenvolvimento e *staging*, incluindo limites configurados de CPU e RAM para simular cenários reais de estresse.

## 5. Testes & Qualidade de Código

* **Testcontainers:** Automação da subida de instâncias reais e efêmeras (PostgreSQL, MongoDB, Redis e RabbitMQ) via Docker para testes de integração 100% confiáveis.
* **ArchUnit:** Testes automatizados de arquitetura para garantir, via CI/CD, que as regras do CQRS, Clean Architecture e isolamento de módulos não sejam violadas pela equipe.
* **JUnit 5, Mockito e REST Assured:** Tríade fundamental para testes unitários, simulação (mocks) de comportamentos e validação de contratos de API *end-to-end*.
