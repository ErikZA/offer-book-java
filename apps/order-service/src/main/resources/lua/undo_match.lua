--[[
  Compensação de Match — Reverte atomicamente um match executado pelo match_engine.lua (AT-17).

  Quando a Fase 3 (commit JPA) falha após um match bem-sucedido no Redis,
  o estado Redis fica inconsistente: contrapartes foram consumidas ou modificadas
  mas o PostgreSQL não registrou o match. Este script restaura o estado Redis
  anterior ao match, re-inserindo as contrapartes consumidas/modificadas.

  IDEMPOTÊNCIA: Executar este script 2× com os mesmos argumentos produz o mesmo
  resultado. Antes de re-inserir, verifica via ZSCORE se o member já existe no
  Sorted Set (cenário de retry). Se já existir com o score esperado, é um no-op
  para aquela contraparte.

  KEYS:
    KEYS[1] = asks_key        (ex: "{vibranium}:asks")
    KEYS[2] = bids_key        (ex: "{vibranium}:bids")
    KEYS[3] = order_index_key (ex: "{vibranium}:order_index")

  ARGV (blocos de 5 argumentos por contraparte a restaurar):
    ARGV[1] = incomingOrderType   "BUY" ou "SELL" (tipo da ordem INGRESSANTE que gerou o match)
    ARGV[2] = incomingOrderId     orderId da ordem ingressante (para remover do book se PARTIAL)
    ARGV[3] = matchCount          número de contrapartes a restaurar
    Para cada contraparte i (0-indexed), base = 4 + i*5:
      ARGV[base]   = counterpartValue   valor pipe-delimited original: "orderId|userId|walletId|qty|correlId|epochMs"
      ARGV[base+1] = counterpartScore   score original (price * 100_000_000)
      ARGV[base+2] = fillType           "FULL", "PARTIAL_ASK" ou "PARTIAL_BID"
      ARGV[base+3] = originalQty        quantidade ORIGINAL da contraparte antes do match
      ARGV[base+4] = counterpartOrderId orderId da contraparte (para atualizar order_index)

  Lógica de restauração por fillType:
    FULL:
      A contraparte foi totalmente consumida (ZREM + HDEL no match_engine.lua).
      Restauração: ZADD com valor original + HSET no order_index.

    PARTIAL_ASK (ingressante é BUY):
      O ASK foi parcialmente consumido — match_engine.lua fez ZREM do membro antigo
      e ZADD de um membro novo com qty reduzida. A contraparte AINDA ESTÁ no book
      com qty residual.
      Restauração: ZREM do membro residual + ZADD do membro original (qty completa)
      + atualiza order_index com o membro original.

    PARTIAL_BID (ingressante é SELL):
      O BID foi parcialmente consumido — mesmo padrão do PARTIAL_ASK mas no bids_key.
      Restauração: ZREM do membro residual + ZADD do membro original (qty completa)
      + atualiza order_index com o membro original.

  Ordem ingressante (PARTIAL do match_engine.lua):
    Se o match_engine.lua retornou PARTIAL, a ordem ingressante foi reinserida no book
    com qty residual. O undo precisa removê-la do book (já que o JPA não commitou,
    a ordem deve voltar ao estado OPEN sem estar no book — a próxima tentativa de match
    começará do zero). A remoção é feita via HGET no order_index + ZREM + HDEL.

  Retorno:
    Número de contrapartes restauradas com sucesso (inteiro).
]]

local incomingOrderType = ARGV[1]
local incomingOrderId   = ARGV[2]
local matchCount        = tonumber(ARGV[3])

-- Divide string por '|'
local function splitPipe(str)
    local parts = {}
    for part in string.gmatch(str, '([^|]+)') do
        table.insert(parts, part)
    end
    return parts
end

local restored = 0

for i = 0, matchCount - 1 do
    local base              = 4 + i * 5
    local counterpartValue  = ARGV[base]
    local counterpartScore  = tonumber(ARGV[base + 1])
    local fillType          = ARGV[base + 2]
    local originalQty       = ARGV[base + 3]
    local counterpartOrderId = ARGV[base + 4]

    -- Determina a key do Sorted Set da contraparte:
    -- Se a ordem ingressante é BUY, contrapartes são ASKs; se SELL, são BIDs.
    local counterpartKey
    if incomingOrderType == 'BUY' then
        counterpartKey = KEYS[1]  -- asks
    else
        counterpartKey = KEYS[2]  -- bids
    end

    -- Fallback de score: se o score passado é 0, tenta obter do order_index.
    -- Para PARTIAL matches o order_index preserva a entrada (com qty atualizada);
    -- para FULL matches o order_index foi HDEL'd e o score do ARGV é obrigatório.
    if counterpartScore == 0 then
        local indexEntry = redis.call('HGET', KEYS[3], counterpartOrderId)
        if indexEntry then
            local sep1 = string.find(indexEntry, '|', 1, true)
            if sep1 then
                local sep2 = string.find(indexEntry, '|', sep1 + 1, true)
                if sep2 then
                    counterpartScore = tonumber(string.sub(indexEntry, sep1 + 1, sep2 - 1))
                end
            end
        end
    end

    if fillType == 'FULL' then
        -- Contraparte foi totalmente consumida: re-inserir com valor e score originais.
        -- Reconstruir o membro com a qty original (o counterpartValue já tem a qty original
        -- pois é o valor que existia ANTES do match).
        local parts = splitPipe(counterpartValue)
        -- Substituir qty pelo originalQty (garantia extra de consistência)
        parts[4] = originalQty
        local restoredValue = table.concat(parts, '|')

        -- Idempotência: verificar se já existe no Sorted Set
        local existing = redis.call('ZSCORE', counterpartKey, restoredValue)
        if not existing then
            redis.call('ZADD', counterpartKey, counterpartScore, restoredValue)
        end
        -- Restaurar order_index
        redis.call('HSET', KEYS[3], counterpartOrderId,
                   counterpartKey .. '|' .. tostring(counterpartScore) .. '|' .. restoredValue)
        restored = restored + 1

    elseif fillType == 'PARTIAL_ASK' or fillType == 'PARTIAL_BID' then
        -- Contraparte foi parcialmente consumida: o match_engine.lua substituiu o membro
        -- por um com qty reduzida. Precisamos remover o membro residual e re-inserir
        -- o membro original com qty completa.

        -- 1. Obter o membro residual atual do order_index
        local indexEntry = redis.call('HGET', KEYS[3], counterpartOrderId)
        if indexEntry then
            local sep2 = string.find(indexEntry, '|', string.find(indexEntry, '|', 1, true) + 1, true)
            if sep2 then
                local currentMember = string.sub(indexEntry, sep2 + 1)
                -- Remover o membro residual
                redis.call('ZREM', counterpartKey, currentMember)
            end
        end

        -- 2. Re-inserir o membro original com qty completa
        local parts = splitPipe(counterpartValue)
        parts[4] = originalQty
        local restoredValue = table.concat(parts, '|')

        -- Idempotência: verificar se já existe
        local existing = redis.call('ZSCORE', counterpartKey, restoredValue)
        if not existing then
            redis.call('ZADD', counterpartKey, counterpartScore, restoredValue)
        end
        -- Atualizar order_index com o membro original
        redis.call('HSET', KEYS[3], counterpartOrderId,
                   counterpartKey .. '|' .. tostring(counterpartScore) .. '|' .. restoredValue)
        restored = restored + 1
    end
end

-- Se o match_engine.lua retornou PARTIAL, a ordem ingressante foi reinserida no book
-- com qty residual. Remover essa inserção residual para que o estado volte ao anterior.
local incomingEntry = redis.call('HGET', KEYS[3], incomingOrderId)
if incomingEntry then
    local sep1 = string.find(incomingEntry, '|', 1, true)
    if sep1 then
        local sep2 = string.find(incomingEntry, '|', sep1 + 1, true)
        if sep2 then
            local bookKey = string.sub(incomingEntry, 1, sep1 - 1)
            local member  = string.sub(incomingEntry, sep2 + 1)
            redis.call('ZREM', bookKey, member)
            redis.call('HDEL', KEYS[3], incomingOrderId)
        end
    end
end

return restored
