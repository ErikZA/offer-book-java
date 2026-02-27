# 🛡️ Guia de Qualidade e Observabilidade: Confiança em Larga Escala

Em um sistema de missão crítica que envolve negociação de ativos como o Vibranium, não podemos confiar apenas em testes manuais. Precisamos de uma infraestrutura que valide a resiliência e a rastreabilidade exigidas.

## 1. Testes de Integração Reais (Testcontainers)

Tradicionalmente, desenvolvedores usam bancos de dados "falsos" (H2 ou mocks) para testes. No entanto, o comportamento de um **Redis Sorted Set** ou de uma transação **ACID no PostgreSQL** é difícil de simular perfeitamente.

* **O Conceito**: O *Testcontainers* permite que, durante a execução dos testes, o sistema suba instâncias reais (em contêineres Docker efêmeros) de todas as nossas bases de dados e mensageria.
* **A Aplicação**: Garantimos que o código Java interaja corretamente com as versões exatas do Postgres, Mongo, Redis e RabbitMQ que serão usadas em produção, eliminando o "na minha máquina funciona".

## 2. Governança Arquitetural (ArchUnit)

Em um monorepo com múltiplos microsserviços e bibliotecas compartilhadas, é fácil um desenvolvedor acidentalmente misturar as camadas (ex: usar uma regra de negócio dentro de uma query de leitura).

* **O Conceito**: O *ArchUnit* é uma biblioteca de teste que analisa a estrutura do código (o bytecode).
* **A Aplicação**: Criamos "testes de arquitetura" que falham o build se alguém tentar, por exemplo, importar uma classe da pasta `command/` dentro da pasta `query/`. Isso garante que o padrão **CQRS** seja respeitado por todo o time para sempre.

## 3. Rastreabilidade Distribuída (Micrometer Tracing)

Com ordens sendo enviadas freneticamente por robôs, entender por que uma transação falhou em um ecossistema de microsserviços é um desafio.

* **O Conceito**: Cada requisição recebe um **Trace ID** único no momento em que entra pelo API Gateway (Kong).
* **A Aplicação**: Esse ID viaja junto com a mensagem pelo RabbitMQ e por todos os bancos de dados. Se uma compra for concretizada na Wallet, mas a ordem não for atualizada no Mongo, podemos buscar pelo Trace ID e ver exatamente onde a cadeia de eventos quebrou.



## 4. Tolerância a Falhas (Resilience4j)

O desafio pede para compreender o que acontece quando diferentes componentes falham.

* **O Conceito**: Implementamos padrões de "disjuntor" (Circuit Breaker) e limites de taxa (Rate Limiting).
* **A Aplicação**: Se o banco de dados PostgreSQL ficar lento sob carga extrema, o *Resilience4j* "abre o circuito" para evitar que o serviço de Wallet trave completamente o sistema, permitindo uma falha graciosa ou uma resposta de erro rápida enquanto o banco se recupera.
