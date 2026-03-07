--[[
  Motor de Match do Order Book — Script Lua com loop de multi-match (AT-3.1.1).

  Executa atomicamente no Redis. A atomicidade de Lua garante que dois BIDs
  concorrentes nunca executem contra o mesmo ASK simultaneamente.

  Loop interno: consome o máximo de liquidez disponível em um único tick atômico.
  Cada iteração remove a contraparte consumida e acumula o resultado em 'matches'.
  Ao final retorna todos os matches em um único array flat, eliminando a necessidade
  de ciclo externo para re-match.

  KEYS:
    KEYS[1] = asks_key        (ex: "{vibranium}:asks")
    KEYS[2] = bids_key        (ex: "{vibranium}:bids")
    KEYS[3] = order_index_key (ex: "{vibranium}:order_index")

  Nota (AT-11.1): As keys usam hash tags {vibranium} para garantir mesmo slot CRC16
  em Redis Cluster. O script em si não conhece as keys — recebe via KEYS[] dinamicamente.

  Nota (AT-04.2): KEYS[3] é o hash auxiliar de índice reverso:
    HSET KEYS[3] <orderId> "<bookKey>|<score>|<member>"
  Permite remoção O(1) via HGET + ZREM + HDEL no remove_from_book.lua.

  ARGV:
    ARGV[1] = orderType     "BUY" ou "SELL"
    ARGV[2] = priceScore    preço * 100_000_000 como inteiro (ex: 500.00 → 50000000000) (AT-3.2.1)
    ARGV[3] = orderValue    "orderId|userId|walletId|qty|correlId|epochMs"
    ARGV[4] = quantityStr   quantidade como string decimal (ex: "10.00000000")

  Retorno (lista de strings):
    {"NO_MATCH"}
      → sem contraparte, ordem inserida no livro

    {"MULTI_MATCH", N, c1val, c1qty, c1fill, c1rem, c2val, c2qty, c2fill, c2rem, ...}
      → N matches, ordem ingressante totalmente preenchida
      → cada match ocupa 4 posições: counterpartValue | matchedQty | fillType | remainingCounterpartQty
      → fillType: "FULL" | "PARTIAL_ASK" | "PARTIAL_BID"
      → remainingCounterpartQty: qty residual da contraparte (ZERO se totalmente consumida)

    {"PARTIAL", N, c1val, c1qty, c1fill, c1rem, ..., remainingIncomingQty}
      → N matches, livro esgotou antes de preencher a ordem ingressante
      → últimos 4 campos + count seguem o mesmo layout de MULTI_MATCH
      → remainingIncomingQty: qty residual da ordem ingressante (Lua já a reinseriu no livro)

  Segurança: MAX_MATCHES = 100 limita iterações para evitar bloqueio prolongado do Redis.
]]

-- Safety: número máximo de iterações por EVAL para evitar bloqueio prolongado do Redis.
-- Em produção, um livro excepcionalmente profundo pode acionar este limite — nesse caso
-- a ordem ingressante fica PARTIAL com o residual reinserido no livro.
local MAX_MATCHES = 100

local orderType    = ARGV[1]
local priceScore   = tonumber(ARGV[2])
local orderValue   = ARGV[3]
local remainingQty = tonumber(ARGV[4])

-- Divide string por '|' — usado para decompor o valor pipe-delimited do Sorted Set.
local function splitPipe(str)
    local parts = {}
    for part in string.gmatch(str, '([^|]+)') do
        table.insert(parts, part)
    end
    return parts
end

-- =========================================================================
-- AT-16: Deduplicação — rejeita inserção duplicada via HEXISTS no order_index.
-- Se o orderId já existe no hash de índice reverso (KEYS[3]), a ordem já está
-- no book. Retorna ALREADY_IN_BOOK imediatamente sem alterar o livro.
-- Isso previne double-booking em cenários de re-entrega rápida onde o consumer
-- processa a Fase 2 (Redis) antes que o ACK alcance o broker.
-- =========================================================================
local incomingOrderId = splitPipe(orderValue)[1]
if redis.call('HEXISTS', KEYS[3], incomingOrderId) == 1 then
    return {'ALREADY_IN_BOOK'}
end

-- Array flat que acumula cada match: {c1val, c1qty, c1fill, c1rem, c2val, ...}
-- Cada match ocupa exatamente 4 posições.
local matches    = {}
local iterations = 0

-- =========================================================================
-- Ramo BUY: consome ASKs do mais barato para o mais caro até esgotar qty
-- =========================================================================
if orderType == 'BUY' then

    while remainingQty > 0 and iterations < MAX_MATCHES do
        -- Menor ASK com preço <= preço do BID
        local candidates = redis.call('ZRANGEBYSCORE', KEYS[1], '0', tostring(priceScore),
                                      'LIMIT', '0', '1')
        if #candidates == 0 then break end   -- livro de ASKs esgotou ou sem preço compatível

        iterations = iterations + 1
        local askValue = candidates[1]
        local askScore = tonumber(redis.call('ZSCORE', KEYS[1], askValue))
        local parts    = splitPipe(askValue)
        local askQty   = tonumber(parts[4])

        if askQty > remainingQty then
            -- ASK parcialmente consumido; BID fully filled nesta iteração
            local remaining  = askQty - remainingQty
            parts[4]         = string.format('%.8f', remaining)
            local newAskVal  = table.concat(parts, '|')
            redis.call('ZREM', KEYS[1], askValue)
            redis.call('ZADD', KEYS[1], askScore, newAskVal)
            -- AT-04.2: atualiza índice — mesmo orderId, novo member com qty residual
            redis.call('HSET', KEYS[3], parts[1],
                       KEYS[1] .. '|' .. tostring(askScore) .. '|' .. newAskVal)
            table.insert(matches, askValue)
            table.insert(matches, string.format('%.8f', remainingQty))
            table.insert(matches, 'PARTIAL_ASK')
            table.insert(matches, string.format('%.8f', remaining))
            remainingQty = 0   -- BID totalmente preenchido; encerra loop

        elseif askQty == remainingQty then
            -- Fill exato: ASK e BID totalmente consumidos
            redis.call('ZREM', KEYS[1], askValue)
            redis.call('HDEL', KEYS[3], parts[1])
            table.insert(matches, askValue)
            table.insert(matches, string.format('%.8f', remainingQty))
            table.insert(matches, 'FULL')
            table.insert(matches, '0.00000000')
            remainingQty = 0   -- BID totalmente preenchido; encerra loop

        else
            -- ASK totalmente consumido; BID ainda tem residual → próxima iteração
            redis.call('ZREM', KEYS[1], askValue)
            redis.call('HDEL', KEYS[3], parts[1])
            table.insert(matches, askValue)
            table.insert(matches, string.format('%.8f', askQty))
            table.insert(matches, 'FULL')
            table.insert(matches, '0.00000000')
            remainingQty = remainingQty - askQty
        end
    end

    if #matches == 0 then
        -- Sem contraparte compatível: insere o BID no livro
        redis.call('ZADD', KEYS[2], priceScore, orderValue)
        -- AT-04.2: popula o índice reverso para permitir remoção O(1) posterior
        redis.call('HSET', KEYS[3], splitPipe(orderValue)[1],
                   KEYS[2] .. '|' .. tostring(priceScore) .. '|' .. orderValue)
        return {'NO_MATCH'}
    end

    if remainingQty > 0 then
        -- Livro esgotou (ou MAX_MATCHES atingido) antes de preencher o BID:
        -- reinsere o residual do BID no livro com qty atualizada.
        local bidParts  = splitPipe(orderValue)
        bidParts[4]     = string.format('%.8f', remainingQty)
        local newBidVal = table.concat(bidParts, '|')
        redis.call('ZADD', KEYS[2], priceScore, newBidVal)
        redis.call('HSET', KEYS[3], bidParts[1],
                   KEYS[2] .. '|' .. tostring(priceScore) .. '|' .. newBidVal)
        -- PARTIAL: N matches + qty residual da ordem ingressante como último elemento
        local result = {'PARTIAL', tostring(#matches / 4)}
        for _, v in ipairs(matches) do table.insert(result, v) end
        table.insert(result, string.format('%.8f', remainingQty))
        return result
    end

    -- MULTI_MATCH: ordem ingressante totalmente preenchida
    local result = {'MULTI_MATCH', tostring(#matches / 4)}
    for _, v in ipairs(matches) do table.insert(result, v) end
    return result

-- =========================================================================
-- Ramo SELL: consome BIDs do mais alto para o mais baixo até esgotar qty
-- =========================================================================
elseif orderType == 'SELL' then

    while remainingQty > 0 and iterations < MAX_MATCHES do
        -- Maior BID com preço >= preço do ASK
        local candidates = redis.call('ZREVRANGEBYSCORE', KEYS[2], '+inf', tostring(priceScore),
                                      'LIMIT', '0', '1')
        if #candidates == 0 then break end   -- livro de BIDs esgotou ou sem preço compatível

        iterations = iterations + 1
        local bidValue = candidates[1]
        local bidScore = tonumber(redis.call('ZSCORE', KEYS[2], bidValue))
        local parts    = splitPipe(bidValue)
        local bidQty   = tonumber(parts[4])

        if bidQty > remainingQty then
            -- BID parcialmente consumido; ASK fully filled nesta iteração
            local remaining  = bidQty - remainingQty
            parts[4]         = string.format('%.8f', remaining)
            local newBidVal  = table.concat(parts, '|')
            redis.call('ZREM', KEYS[2], bidValue)
            redis.call('ZADD', KEYS[2], bidScore, newBidVal)
            -- AT-04.2: atualiza índice — mesmo orderId, novo member com qty residual
            redis.call('HSET', KEYS[3], parts[1],
                       KEYS[2] .. '|' .. tostring(bidScore) .. '|' .. newBidVal)
            table.insert(matches, bidValue)
            table.insert(matches, string.format('%.8f', remainingQty))
            table.insert(matches, 'PARTIAL_BID')
            table.insert(matches, string.format('%.8f', remaining))
            remainingQty = 0   -- ASK totalmente preenchido; encerra loop

        elseif bidQty == remainingQty then
            -- Fill exato: BID e ASK totalmente consumidos
            redis.call('ZREM', KEYS[2], bidValue)
            redis.call('HDEL', KEYS[3], parts[1])
            table.insert(matches, bidValue)
            table.insert(matches, string.format('%.8f', remainingQty))
            table.insert(matches, 'FULL')
            table.insert(matches, '0.00000000')
            remainingQty = 0   -- ASK totalmente preenchido; encerra loop

        else
            -- BID totalmente consumido; ASK ainda tem residual → próxima iteração
            redis.call('ZREM', KEYS[2], bidValue)
            redis.call('HDEL', KEYS[3], parts[1])
            table.insert(matches, bidValue)
            table.insert(matches, string.format('%.8f', bidQty))
            table.insert(matches, 'FULL')
            table.insert(matches, '0.00000000')
            remainingQty = remainingQty - bidQty
        end
    end

    if #matches == 0 then
        -- Sem contraparte compatível: insere o ASK no livro
        redis.call('ZADD', KEYS[1], priceScore, orderValue)
        -- AT-04.2: popula o índice reverso para permitir remoção O(1) posterior
        redis.call('HSET', KEYS[3], splitPipe(orderValue)[1],
                   KEYS[1] .. '|' .. tostring(priceScore) .. '|' .. orderValue)
        return {'NO_MATCH'}
    end

    if remainingQty > 0 then
        -- Livro esgotou (ou MAX_MATCHES atingido) antes de preencher o ASK:
        -- reinsere o residual do ASK no livro com qty atualizada.
        local askParts  = splitPipe(orderValue)
        askParts[4]     = string.format('%.8f', remainingQty)
        local newAskVal = table.concat(askParts, '|')
        redis.call('ZADD', KEYS[1], priceScore, newAskVal)
        redis.call('HSET', KEYS[3], askParts[1],
                   KEYS[1] .. '|' .. tostring(priceScore) .. '|' .. newAskVal)
        -- PARTIAL: N matches + qty residual da ordem ingressante como último elemento
        local result = {'PARTIAL', tostring(#matches / 4)}
        for _, v in ipairs(matches) do table.insert(result, v) end
        table.insert(result, string.format('%.8f', remainingQty))
        return result
    end

    -- MULTI_MATCH: ordem ingressante totalmente preenchida
    local result = {'MULTI_MATCH', tostring(#matches / 4)}
    for _, v in ipairs(matches) do table.insert(result, v) end
    return result

end

return {'NO_MATCH'}
