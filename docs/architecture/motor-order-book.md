# 📑 Guia de Integração e Lógica do Motor de Match

Para atingir a escala de **5.000 trades por segundo**, não podemos confiar em comunicações lentas. Nossa arquitetura utiliza uma mistura de **mensageria assíncrona** (para resiliência) e **scripts atômicos no Redis** (para velocidade).

## 1. Webhooks e Integrações entre Serviços

Diferente de sistemas simples onde um serviço chama o outro e espera a resposta (síncrono), aqui usamos o **RabbitMQ** para que os serviços "dancem" conforme os eventos acontecem.

### Fluxo de Onboarding (Keycloak ➔ Wallet)

* 
**O que acontece:** Quando um novo usuário se cadastra no Keycloak, ele dispara uma notificação (Webhook).


* 
**Ação:** O `Wallet Service` recebe esse aviso e cria automaticamente uma carteira vinculada 1:1 ao ID do usuário.



### Fluxo de Ordem (Order ➔ Wallet)

* 
**O que acontece:** O usuário envia uma ordem de compra pelo `Order Service`.


* 
**Ação:** Antes de tentar negociar, o `Order Service` avisa a `Wallet` via RabbitMQ: "Ei, verifique se esse usuário tem saldo e bloqueie o valor".


* 
**Resposta:** A `Wallet` responde com um evento de `FundosBloqueados` para que a ordem possa seguir para o mercado.



### Fluxo de Liquidação (Order ➔ Wallet)

* 
**O que acontece:** O motor de match encontra um par (Comprador e Vendedor).


* **Ação:** O `Order Service` publica o evento `MatchRealizado`.
* 
**Conclusão:** A `Wallet` ouve isso e faz a troca: tira o Vibranium de um, coloca no outro, e faz o mesmo com os Reais.



---

## 2. O Script Lua: O Motor Obrigatório ⚡

O desafio exige que o **Livro de Ofertas não admita concorrência**. Se dois robôs tentarem "morder" a mesma oferta de venda ao mesmo tempo, apenas um pode ganhar. O Script Lua resolve isso sendo **atômico**.

### Por que ele é obrigatório?

1. **Velocidade Máxima:** O script roda dentro da memória do Redis. Ele não precisa "viajar" pela rede para tomar decisões.


2. **Fila Indiana:** O Redis processa um script Lua por vez. Isso impede que duas ordens de compra "atropelem" a mesma ordem de venda.


3. **Tudo ou Nada:** Ou o match acontece por completo e as ordens são atualizadas, ou nada muda. Não existe meio-termo que cause erro de saldo.

### O que o script faz na prática?

1. **Recebe a Ordem:** O Java envia (ID, Lado, Preço, Quantidade) para o Redis.
2. **Compara o Topo:** Se for uma compra, o script olha o topo da fila de vendas (`asks`).
3. **Verifica o Preço:** Se o preço de compra for igual ou maior que o de venda, ele executa o match.
4. **Limpa a Fila:** Se a quantidade de uma oferta acabar, o script a remove do Redis (`ZREM`).
5. **Avisa o Java:** O script retorna uma lista de quem comprou de quem e quanto sobrou.

---

## 3. Cuidados Críticos (Para não quebrar o sistema)

Para manter a integridade sob pressão de milhares de acessos:

* **Idempotência:** A `Wallet` deve ser esperta. Se ela receber o mesmo aviso de "Match" duas vezes por erro de rede, ela deve processar apenas a primeira e ignorar a segunda.
* 
**Transactional Outbox:** Na `Wallet`, o registro do débito no banco e a mensagem de aviso para o resto do sistema devem ser salvos na **mesma transação**. Se um falhar, o outro não acontece.


* 
**Virtual Threads (Java 21):** Como teremos milhares de robôs enviando ordens, usaremos as threads virtuais para que o servidor não fique "travado" esperando o Redis ou o RabbitMQ responder.
