# 🗄️ Guia de Arquitetura de Dados: A Ferramenta Certa para o Trabalho Certo

Guia de banco de dados do nosso Livro de Ofertas!

O nosso desafio exige suportar **5000 transações por segundo** e ter **rastreabilidade** do histórico. Se colocarmos tudo em um único banco, ele vai travar. Para resolver isso, usamos um conceito chamado **Persistência Poliglota**.

**Persistência Poliglota** significa simplesmente: "Usar o banco de dados que é melhor naquilo que precisamos fazer". No nosso ecossistema, temos **5 bases de dados lógicas**. Vamos entender cada uma delas!

---

## 1. PostgreSQL (Microsserviço Wallet) 🐘

**O Guardião do Dinheiro**

* **O que é:** Um banco de dados relacional clássico, organizado em linhas e colunas (tabelas).

**Para que usamos:** Para guardar o saldo da carteira dos usuários (Reais e Vibranium) e gerenciar os bloqueios de saldo (quando o dinheiro fica "em trade").

* **Por que escolhemos ele para isso?** Quando lidamos com dinheiro, precisamos de uma garantia chamada **ACID** (Atomicidade, Consistência, Isolamento, Durabilidade). O Postgres é excelente em "trancar" uma linha (Row-Level Lock).
*Exemplo:* Se dois robôs tentarem gastar os mesmos R$ 100,00 da sua carteira no exato mesmo milissegundo, o Postgres coloca um em fila de espera e garante que o saldo nunca fique negativo. Ele é lento para milhares de buscas complexas, mas é **infalível** para cálculos financeiros.

### Topologia de Alta Disponibilidade no Staging (AT-5.1.3)

No ambiente de staging, o PostgreSQL opera em topologia de **Streaming Replication**:

| Nó | Container | Modo | Portas |
|---|---|---|---|
| Primary | `postgres-primary` | Write/Read — `wal_level=replica` | 5432 |
| Hot Standby 1 | `postgres-replica-1` | Somente leitura — `hot_standby=on` | 5433 |
| Hot Standby 2 | `postgres-replica-2` | Somente leitura — `hot_standby=on` | 5434 |

**Como funciona:**
1. O primary cria o usuário `replicator` via `pg-primary-init.sh` (rodado no `docker-entrypoint-initdb.d/`).
2. Cada réplica usa `pg-replica-entrypoint.sh` para executar `pg_basebackup --wal-method=stream`, criando `standby.signal` e configurando `primary_conninfo` no `postgresql.auto.conf`.
3. **Todos os `wallet-service` (1, 2 e 3) apontam para o primary** para garantir que writes nunca sejam direcionados para um hot_standby (que rejeitaria com erro `cannot execute INSERT in a read-only transaction`).

**Monitoramento:** `SELECT * FROM pg_stat_replication;` no primary deve exibir 2 réplicas com `state = 'streaming'`.

## 2. MongoDB (Microsserviço Order - Leitura/Histórico) 🍃

**O Diário de Bordo (CQRS - Lado Read)**

* **O que é:** Um banco de dados NoSQL Orientado a Documentos. Ele não usa tabelas; ele salva os dados como arquivos JSON soltos (chamados de documentos).
* 
**Para que usamos:** Para guardar o histórico imutável das transações (a rastreabilidade)  e servir os dados para a API quando o usuário quer ver suas ordens.


* **Por que escolhemos ele para isso?**
No padrão CQRS, separamos a escrita da leitura. O MongoDB é incrivelmente rápido para ler dados porque ele salva a ordem "pronta". Não precisamos fazer `JOIN` entre 5 tabelas diferentes para montar a tela do usuário. Se o usuário quer o histórico, o Mongo simplesmente cospe o JSON da ordem instantaneamente.

## 3. Redis (Microsserviço Order - Motor de Match) ⚡

**O Cérebro Rápido (In-Memory)**

* **O que é:** Um banco de dados que funciona inteiramente na Memória RAM do servidor.
* **Para que usamos:** Para ser o "Motor do Livro de Ofertas" (Order Book). É aqui que colocamos as ordens de quem quer comprar e quem quer vender para ver se elas "dão match".
* **Por que escolhemos ele para isso?**
A memória RAM é absurdamente mais rápida que um disco rígido (onde o Postgres e o Mongo salvam os dados). Como a plataforma vai receber robôs colocando ordens freneticamente, precisamos cruzar dados em milissegundos. O Redis possui uma estrutura chamada **Sorted Sets** que já guarda as ordens ordenadas pelo melhor preço e pelo tempo de chegada automaticamente!



## 4. PostgreSQL (Keycloak - Identity Provider) 🔐

**O Porteiro do Prédio**

* **O que é:** Outra base relacional (PostgreSQL), mas exclusiva para o serviço do Keycloak.
* **Para que usamos:** Para salvar e-mails, senhas criptografadas (hashes), roles (permissões) e configurações dos tokens JWT.
* **Por que escolhemos ele para isso?**
O Keycloak é um software pronto que roda separado dos nossos microsserviços. Por padrão, ele exige um banco de dados relacional sólido para armazenar a identidade dos usuários de forma segura. Nossos microsserviços (`Order` e `Wallet`) **nunca** acessam esse banco diretamente; eles apenas confiam no token que o Keycloak gerou.

## 5. PostgreSQL (Kong - API Gateway) 🦍

**O Guarda de Trânsito**

* **O que é:** Mais uma base relacional, exclusiva para o Kong.
* **Para que usamos:** Para salvar as rotas (ex: "quem chama `/api/orders` vai para o microsserviço X") e as regras de segurança (Rate Limiting, para barrar ataques de negação de serviço).
* **Por que escolhemos ele para isso?**
Assim como o Keycloak, o Kong precisa de um lugar seguro para salvar suas configurações. Ele lê as rotas desse Postgres e joga para a memória dele para rotear as requisições na velocidade da luz.

---

### 💡 Resumo para o Desenvolvedor (Dica Prática)

**"Espera aí, eu vou ter que subir 5 servidores de banco de dados na minha máquina para trabalhar?!"**

**Não!** Fique tranquilo. Na vida real (Produção na AWS/GCP), sim, teremos servidores separados para não misturar os recursos.
Mas, no seu ambiente de desenvolvimento local (usando o nosso `infra/docker-compose.yml`), nós subiremos apenas **3 contêineres** de banco:

1. **1 Contêiner MongoDB** (Para o histórico de Ordens).
2. **1 Contêiner Redis** (Para o Motor de Match).
3. **1 Contêiner PostgreSQL**. Dentro desse único Postgres, criaremos três *databases* lógicos e separados (`wallet_db`, `keycloak_db`, `kong_db`). Assim, a gente economiza a RAM do seu computador de trabalho, mas mantém a arquitetura isolada do jeito certo!