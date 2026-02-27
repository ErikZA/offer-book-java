# Documentação de Arquitetura: Event Storming do Livro de Ofertas (Order Book)

Bem-vindo à documentação inicial do nosso MVP! O objetivo desta plataforma é permitir aos usuários negociarem o ativo **Vibranium** através de um Livro de Ofertas.

Para garantir que o sistema seja escalável e suporte até 5000 trades por segundo, utilizamos uma arquitetura orientada a eventos (Event-Driven). Este documento mapeia exatamente o que acontece no sistema, quem faz o quê, e como as informações fluem.

---

## 1. Dicionário de Termos (Para Iniciantes)

Antes de mergulharmos no fluxo, é importante entender os três conceitos principais do nosso Event Storming:

* **Atores / Políticas (Amarelo/Lilás):** São as pessoas, sistemas externos ou regras de negócio que tomam decisões e disparam ações.
* **Comandos (Azul):** É uma intenção. É quando alguém "pede" para o sistema fazer algo (Ex: *CriarOrdem*).
* **Eventos de Domínio (Laranja):** É um fato que já ocorreu no passado. O sistema avisa que algo mudou (Ex: *OrdemCriada*).

---

## 2. Atores e Sistemas Envolvidos

Quem (ou o que) interage com o nosso sistema?

* 
**Usuários e Robôs:** Clientes finais que enviam ordens de compra e venda freneticamente.


* **Identity Provider (Keycloak):** Sistema externo responsável por gerenciar logins, senhas e os dados cadastrais seguros do usuário.
* 
**Microsserviço de Wallet (Carteira):** Gerencia o saldo em Reais e a quantidade de Vibranium de cada usuário.


* **Microsserviço de Order (Livro de Ofertas):** Recebe as intenções de compra/venda e envia para processamento.
* **Motor de Match (Redis):** O "cérebro" do livro de ofertas. Ele cruza as ordens de quem quer comprar com as de quem quer vender.

---

## 3. A Regra de Ouro: Relacionamento 1:1 (Usuário ↔ Carteira)

Embora o foco do MVP seja o Orderbook , estruturamos a base de usuários para garantir segurança e consistência.

* Todo usuário criado no sistema de autenticação (Keycloak) possui **exatamente uma** carteira no banco de dados principal (PostgreSQL).
* Esta carteira nasce zerada (0 Reais, 0 Vibranium) e é o único local onde os saldos são alterados.

---

## 4. Catálogo de Comandos (Ações)

Estes são os pedidos que entram no nosso sistema:

* `SolicitarRegistroUsuario`: Pedido para criar uma nova conta na plataforma.
* `CriarCarteira`: Pedido interno para gerar o banco de dados da carteira daquele novo usuário.
* `SolicitarOrdemCompra`: Pedido do usuário para comprar Vibranium usando seus Reais.
* `SolicitarOrdemVenda`: Pedido do usuário para vender seu Vibranium em troca de Reais.
* 
`BloquearFundosEmTrade`: Pedido interno para "congelar" o dinheiro ou o Vibranium enquanto a ordem não é executada, separando o saldo livre do saldo "em trade".


* `ExecutarMatch`: Pedido gerado pelo motor informando que encontrou um comprador e um vendedor compatíveis.

---

## 5. Catálogo de Eventos (Fatos Ocorridos)

Estas são as mensagens que o sistema espalha (via RabbitMQ) após um comando dar certo:

* `UsuarioRegistrado`: O Keycloak confirmou a criação da conta.
* `CarteiraCriada`: O banco de dados confirmou que a carteira 1:1 está pronta.
* `OrdemCompraRecebida` / `OrdemVendaRecebida`: O sistema aceitou a intenção do usuário.
* `FundosEmTradeBloqueados`: O saldo foi separado com sucesso para garantir que o usuário tem fundos para bancar a ordem.
* `OrdemAdicionadaAoLivro`: A ordem foi salva e está aguardando um par compatível.
* `MatchRealizado`: Ocorreu o cruzamento exato entre uma compra e uma venda.
* 
`CompraConcretizada`: O Vibranium foi adicionado e os Reais foram subtraídos da carteira.


* 
`VendaConcretizada`: Os Reais foram adicionados e o Vibranium foi subtraído da carteira.



---

## 6. A Linha do Tempo (Como as coisas acontecem na prática)

Imagine o caminho feliz de um usuário chegando agora na plataforma:

1. **O Cadastro (Fase Zero)**
* O usuário se cadastra. O sistema executa `SolicitarRegistroUsuario`.
* Dispara o evento: `UsuarioRegistrado`.
* A Wallet ouve isso, executa `CriarCarteira` e avisa: `CarteiraCriada`.


2. **A Intenção de Negócio**
* O usuário decide comprar Vibranium e clica no botão. O comando `SolicitarOrdemCompra` é acionado.
* O microsserviço de Order valida e avisa o resto do sistema: `OrdemCompraRecebida`.


3. **Garantia de Fundos (Evitando fraudes)**
* A Wallet ouve o pedido de compra e congela o valor exato em Reais da carteira (`BloquearFundosEmTrade`).
* A Wallet avisa: `FundosEmTradeBloqueados`. O dinheiro agora está seguro e reservado para essa operação.


4. **A Fila do Livro de Ofertas**
* Como os fundos estão garantidos, a ordem é enviada ao Motor (Redis).
* O sistema avisa: `OrdemAdicionadaAoLivro`.


5. **O Encontro (Match)**
* O Motor (Redis) encontra alguém vendendo Vibranium pelo mesmo preço. Ele dispara o comando `ExecutarMatch`.
* O Motor avisa: `MatchRealizado`.


6. **A Liquidação (Recebendo o que é seu)**
* A Wallet ouve que o match aconteceu e faz as contas finais no banco de dados.
* Para quem comprou, emite `CompraConcretizada` (ganha Vibranium, perde Reais).


* Para quem vendeu, emite `VendaConcretizada` (ganha Reais, perde Vibranium).


* Um histórico imutável é salvo no MongoDB para garantir a rastreabilidade exigida pelo desafio.