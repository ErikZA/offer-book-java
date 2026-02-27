# 🛡️ Guia de Infraestrutura: Kong Gateway & Keycloak IAM

Para que o nosso Livro de Ofertas seja resiliente e seguro, não deixamos que os microsserviços de negócio (`Order` e `Wallet`) cuidem de login ou de barrar ataques. Usamos ferramentas especialistas que possuem seus próprios bancos de dados para guardar configurações e identidades.

---

## 1. Keycloak IAM (Identity & Access Management) 🔐

O Keycloak é o responsável por saber **quem** é o usuário. Ele é o "emissor de passaportes" (Tokens JWT) do nosso sistema.

### Como ele integra com o banco de dados?

O Keycloak utiliza o **PostgreSQL** para armazenar tudo o que é persistente. Na maioria das vezes, ele cria suas tabelas automaticamente na primeira inicialização (Auto-Migration), mas é vital entender os dados que ele guarda para a nossa integração 1:1. 

* **Criação Automática:** Ao conectar o Keycloak ao banco `keycloak_db`, ele criará mais de 90 tabelas. Você não deve alterá-las manualmente.
* **Tabelas e Campos Chave (Conceitual):**
* `USER_ENTITY`: Onde fica o `ID` (UUID) do usuário. Esse ID é o que usamos no `wallet-service` para vincular uma carteira ao dono. 


* `CREDENTIAL`: Onde ficam os hashes das senhas.
* `CLIENT`: Configurações do nosso app (quem pode pedir login).



### Integração com o código:

O Keycloak não "fala" com o seu banco de dados de ordens. Ele assina um **Token JWT**. O seu código Java apenas lê esse token e confia que, se o Keycloak assinou, o usuário é quem diz ser.

---

## 2. Kong API Gateway 🦍

O Kong é o "guarda de trânsito". Ele recebe todas as chamadas da internet e decide para onde enviá-las. Ele também barra robôs que tentam enviar ordens rápido demais (Rate Limiting). 

### Como ele integra com o banco de dados?

O Kong utiliza o **PostgreSQL** para salvar as regras de roteamento. Se o banco do Kong cair, ele continua funcionando com as regras que já tem na memória, mas você não consegue criar novas rotas.

* **Database Mode:** Usaremos o Kong no modo `database-backed`.
* **Criação de Tabelas:** O Kong exige que você execute um comando de "bootstrap" (`kong migrations bootstrap`) antes de subir o serviço. Isso criará as tabelas de rotas e plugins.

### Configurações Necessárias (Plugins):

Diferente de tabelas de negócio, o Kong guarda **configurações** no banco. Para o nosso MVP, precisamos garantir que estas regras existam no banco do Kong:

1. **Plugin de JWT:** Registra a chave pública do Keycloak para validar tokens sem sair da rede.
2. 
**Plugin de Rate Limiting:** Configura o limite (ex: 100 requisições/segundo por IP) para evitar que um único robô derrube o sistema. 



---

## 3. O que fazer se as tabelas não aparecerem? (Scripts Manuais)

Embora o Keycloak e o Kong sejam "autossuficientes", se você estiver configurando o ambiente do zero em um servidor onde eles não têm permissão de "ADMIN", você precisará garantir que os bancos (Databases) existam.

### No PostgreSQL (Root):

Você deve criar os bancos e usuários manualmente antes de subir o Docker, para que as ferramentas possam se conectar:

```sql
-- Para o Keycloak
CREATE DATABASE keycloak_db;
CREATE USER keycloak_user WITH PASSWORD 'password';
GRANT ALL PRIVILEGES ON DATABASE keycloak_db TO keycloak_user;

-- Para o Kong
CREATE DATABASE kong_db;
CREATE USER kong_user WITH PASSWORD 'password';
GRANT ALL PRIVILEGES ON DATABASE kong_db TO kong_user;

```

### Campos Específicos para Integração 1:1

Para que a sua carteira (`Wallet`) funcione, você precisará salvar no seu banco de dados de negócio (`wallet_db`) um campo que ligue ao Keycloak. 

**Na sua tabela `TB_WALLET` do PostgreSQL:** 

* **Campo:** `user_id`
* **Tipo:** `UUID` (ou `VARCHAR(36)`)
* **Obrigatório:** Sim (`NOT NULL`)
* **Único:** Sim (`UNIQUE`) - Isso garante que 1 usuário tenha apenas 1 carteira.

---

## 💡 Resumo

* **Keycloak:** Não mexa nas tabelas dele. Apenas use o `sub` (Subject ID) que vem dentro do Token JWT para identificar o usuário no seu código. 


* **Kong:** Use o banco dele apenas para salvar as rotas. Se você deletar o banco do Kong, você perde as rotas da API, mas não perde os dados dos usuários ou as ordens.
* 
**Sincronia:** O ID do usuário nasce no Keycloak e é "carimbado" na sua tabela de Carteira e na sua tabela de Ordens para que saibamos de quem é o Vibranium. 