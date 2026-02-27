# 🍃 Guia Conceitual de Modelagem: Microsserviço Order (MongoDB)

No nosso MVP, vamos permitir aos usuários negociarem Vibranium na plataforma. Para suportar isso de forma saudável, adotamos o padrão **CQRS (Segregação de Responsabilidade de Comando e Consulta)**.

Enquanto o PostgreSQL é o nosso "cofre" inflexível e o Redis é o nosso motor de alta velocidade, o **MongoDB** assume o papel de **Vitrine de Leitura (Read Model)** e **Diário de Bordo**.

Neste guia conceitual, vamos entender o paradigma orientado a documentos e como ele resolve os problemas de performance e auditoria do nosso sistema.

---

## 1. O Paradigma Orientado a Documentos (Adeus, JOINs)

Em bancos de dados relacionais, nós dividimos as informações em várias tabelas para evitar repetição (normalização). Para exibir uma ordem completa na tela de um usuário, um banco relacional precisaria "juntar" (fazer `JOIN`) a tabela de Ordens, a tabela de Status, e a tabela de Histórico. Isso custa processamento.

A plataforma exige que a escala seja muito importante, suportando pelo menos 5000 requisições por segundo. Para a leitura ser instantânea, o MongoDB adota a estratégia **Desnormalizada**.

* **O Conceito de Documento:** Em vez de tabelas, o Mongo guarda **Documentos** (arquivos estruturados, similares ao JSON). Um documento contém *tudo* o que o Frontend precisa saber sobre aquela ordem em um único lugar.
* **Leitura O(1):** Quando o usuário entra no aplicativo e clica em "Detalhes da Ordem", o banco não faz cálculos. Ele vai direto no documento específico e o devolve inteiro de uma só vez.

## 2. CQRS na Prática: A Visão Materializada (Projection)

É fundamental entender que, na nossa arquitetura, **o usuário nunca grava dados diretamente no MongoDB.** O fluxo conceitual de atualização (escrita) segue este caminho:

1. A intenção de compra do usuário entra no sistema e vai para o Motor de Match (Redis) ou valida saldos na Wallet (Postgres).
2. Quando algo de fato acontece (ex: ocorreu um *Match*), um **Evento** é disparado no RabbitMQ.
3. O microsserviço *Order* possui um "Ouvinte" (Query Handler) que escuta esse evento de forma totalmente assíncrona.
4. Este ouvinte pega o evento e **atualiza a "foto" da ordem no MongoDB**.

Essa "foto" atualizada é o que chamamos de **Visão Materializada (Projection)**. O MongoDB serve como um espelho de leitura otimizado e pré-calculado do que já aconteceu no sistema central.

## 3. Rastreabilidade e o Padrão de Documentos Embutidos

É muito importante também que as transações tenham rastreabilidade, ou seja, um histórico das transações.

Para resolver isso de forma elegante no MongoDB, utilizamos o padrão de **Documentos Embutidos (Embedded Documents)**:

* Em vez de criar uma coleção separada para "Logs de Transação", o próprio documento da Ordem possui uma lista interna (um *array*) chamada `history`.
* Cada vez que a ordem muda de estado (ex: de `PENDING` para `PARTIAL_FILLED`), nós apenas empurramos um novo registro para dentro desta lista contendo a data, hora, e o que aconteceu.
* **O Benefício:** O histórico da ordem nasce, cresce e morre junto com ela. Isso garante uma auditoria perfeita e isolada. Se houver um problema com a ordem do usuário "A", basta puxar o documento dele e toda a linha do tempo estará lá, embutida cronologicamente.

## 4. Design para Performance: A Importância Estratégica dos Índices

Como muitos usuários utilizarão robôs para colocar ordens freneticamente, a performance e a concorrência devem ser muito bem pensadas. Se esses robôs precisarem consultar o status de suas ordens o tempo todo, a base de leitura será severamente bombardeada.

Para que o MongoDB não colapse buscando ordens no meio de milhões de documentos, a modelagem conceitual exige a criação prévia de **Índices**:

* **Índice Primário e de Usuário:** O sistema cria rotas de acesso ultrarrápidas na memória apontando diretamente para o "Dono da Ordem" (`userId`) ordenado por "Data de Criação" (`createdAt`).
* Dessa forma, quando o robô do usuário perguntar "Quais são minhas últimas 50 ordens abertas?", o MongoDB não precisa ler a base inteira; ele vai direto ao índice do usuário e devolve a resposta em frações de milissegundo.
