### 1. Fase Zero: Onboarding e Relação 1:1

Neste fluxo, mostramos como um usuário é criado e como a sua carteira nasce zerada no banco relacional.

```mermaid
sequenceDiagram
    autonumber
    actor Cliente as Usuário / Robô
    participant Kong as Kong (API Gateway)
    participant IAM as Keycloak (Identity)
    participant MQ as RabbitMQ (Broker)
    participant Wallet as Wallet Service (Spring Boot)
    participant PG as PostgreSQL (ACID)

    Cliente->>Kong: POST /api/v1/register (Dados do Usuário)
    Kong->>IAM: Encaminha requisição de criação
    IAM-->>Kong: Usuário criado com Sucesso (Retorna UUID)
    
    Note over IAM, MQ: Keycloak emite evento via plugin/webhook
    IAM->>MQ: Publica Evento: UsuarioRegistrado
    
    MQ-->>Wallet: Consome Evento (Virtual Thread)
    Wallet->>PG: BEGIN TRANSACTION
    Wallet->>PG: INSERT: Nova Carteira (Saldo R$ 0, 0 Vibranium)
    Note over Wallet, PG: Relacionamento 1:1 vinculado ao UUID do Keycloak
    Wallet->>PG: COMMIT
    
    Wallet->>MQ: Publica Evento: CarteiraCriada
    Kong-->>Cliente: 201 Created

```

---

### 2. Fase Um: Intenção de Ordem e Garantia de Saldo

O usuário quer comprar ou vender. Precisamos garantir que ele está autenticado e que tem saldo suficiente antes de enviar a ordem para o motor de match.

*Nota: Aqui aplicamos o padrão **Transactional Outbox** no PostgreSQL para não perder eventos.*

```mermaid
sequenceDiagram
    autonumber
    actor Cliente as Usuário / Robô
    participant Kong as Kong (API Gateway)
    participant Order as Order Service (Spring Boot)
    participant MQ as RabbitMQ (Broker)
    participant Wallet as Wallet Service (Spring Boot)
    participant PG as PostgreSQL (ACID)

    Cliente->>Kong: POST /api/v1/orders (JWT Token)
    Note over Kong: Valida JWT via Keycloak JWKS
    Kong->>Order: Encaminha POST /orders (Command)
    
    Order->>MQ: Publica Evento: OrdemRecebida
    Order-->>Kong: 202 Accepted (Em processamento)
    Kong-->>Cliente: 202 Accepted
    
    MQ-->>Wallet: Consome Evento: OrdemRecebida
    Wallet->>PG: BEGIN TRANSACTION
    Wallet->>PG: UPDATE: Bloqueia saldo em Reais/Vibranium (Row-level lock)
    Note over Wallet, PG: Transactional Outbox Pattern
    Wallet->>PG: INSERT INTO outbox_table (Evento: FundosBloqueados)
    Wallet->>PG: COMMIT
    
    Note over Wallet, MQ: Job assíncrono lê a tabela Outbox
    Wallet->>MQ: Publica Evento: FundosBloqueados

```

---

### 3. Fase Dois: Motor de Match (Livro de Ofertas)

Com o saldo garantido, a ordem vai para o cérebro da operação: o **Redis**. Usamos as *Sorted Sets* do Redis porque elas ordenam as ofertas por preço e tempo na velocidade da memória RAM.

```mermaid
sequenceDiagram
    autonumber
    participant MQ as RabbitMQ (Broker)
    participant Order as Order Service (Spring Boot)
    participant Redis as Redis (Sorted Sets)

    MQ-->>Order: Consome Evento: FundosBloqueados
    
    Order->>Redis: ZADD buy_orders (Preço + Timestamp, Ordem)
    Note over Order, Redis: Insere na fila ordenada de compras ou vendas
    Order->>Redis: LUA Script / Busca de Oportunidades
    
    alt Encontrou contraparte (Match!)
        Redis-->>Order: Retorna Ordens Cruzadas
        Order->>MQ: Publica Evento: MatchRealizado (Ordem A e B)
    else Não encontrou (Fica no Livro)
        Redis-->>Order: Nenhuma ação
        Order->>MQ: Publica Evento: OrdemAdicionadaAoLivro
    end

```

---

### 4. Fase Três: Liquidação (Settlement) e Histórico

O match aconteceu! Agora precisamos consolidar os saldos e guardar o histórico inviolável para rastreabilidade (CQRS).

```mermaid
sequenceDiagram
    autonumber
    participant MQ as RabbitMQ (Broker)
    participant Wallet as Wallet Service (Spring Boot)
    participant PG as PostgreSQL (ACID)
    participant Order as Order Service (Spring Boot)
    participant Mongo as MongoDB (Histórico)

    MQ-->>Wallet: Consome Evento: MatchRealizado
    
    Wallet->>PG: BEGIN TRANSACTION
    Note over Wallet, PG: Compra: - Reais, + Vibranium | Venda: + Reais, - Vibranium
    Wallet->>PG: UPDATE: Debita saldo bloqueado e Credita saldos reais
    Wallet->>PG: INSERT INTO outbox_table (TransacaoConcretizada)
    Wallet->>PG: COMMIT
    
    Wallet->>MQ: Publica Eventos: CompraConcretizada / VendaConcretizada
    
    MQ-->>Order: Consome Eventos de Concretização
    Note over Order, Mongo: Persistência Poliglota e CQRS (Lado da Leitura)
    Order->>Mongo: INSERT / UPDATE: Documento da Ordem (Status = COMPLETED)
    
    Note right of Mongo: Histórico salvo para auditoria e<br/>consultas da API (GET /orders)

```

### 💡 Dicas de Leitura para a Equipe:

1. **Kong + Keycloak:** Eles retiram o peso de autenticação dos microsserviços de negócio. O *Order* e o *Wallet* já recebem a requisição sabendo que o usuário existe e é válido.
2. **RabbitMQ no Centro:** Note como quase todas as passagens de bastão passam pelo RabbitMQ. Isso garante que, se o serviço *Wallet* cair sob o pico de 5000 requests/s, o RabbitMQ segura os eventos na fila até ele voltar (Resiliência).
3. **Bancos Diferentes (Poliglota):** O PostgreSQL (ACID) cuida exclusivamente do dinheiro (para não sumir um centavo), enquanto o MongoDB guarda os documentos complexos (JSON) das ordens e o Redis faz a matemática rápida do cruzamento.