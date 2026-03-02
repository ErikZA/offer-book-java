--[[
  Motor de Match do Order Book — Script Lua executado atomicamente no Redis.

  Localiza e executa o cruzamento de uma ordem contra o lado oposto do livro.
  A atomicidade de Lua no Redis garante que dois BIDs concorrentes nunca
  executem contra o mesmo ASK simultaneamente.

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
    ARGV[2] = priceScore    preço * 1_000_000 como inteiro (ex: 500.00 → 500000000)
    ARGV[3] = orderValue    "orderId|userId|walletId|qty|correlId|epochMs"
    ARGV[4] = quantityStr   quantidade como string decimal (ex: "10.00000000")

  Retorno (lista de strings):
    {"NO_MATCH"}                          → sem contraparte, ordem inserida no livro
    {"MATCH", counterpartValue, matchedQty, fillType, remainingCounterpartQty}
      fillType: "FULL" | "PARTIAL_ASK" | "PARTIAL_BID"
      remainingCounterpartQty: qty residual da contraparte (ZERO quando contraparte
        foi totalmente consumida; positivo quando contraparte tem saldo remanescente).
]]

local orderType  = ARGV[1]
local priceScore = tonumber(ARGV[2])
local orderValue = ARGV[3]
local orderQty   = tonumber(ARGV[4])

-- Divide string por '|'
local function splitPipe(str)
    local parts = {}
    for part in string.gmatch(str, '([^|]+)') do
        table.insert(parts, part)
    end
    return parts
end

if orderType == 'BUY' then
    -- Encontra o ASK mais barato com preço <= preço do BID
    local candidates = redis.call('ZRANGEBYSCORE', KEYS[1], '0', tostring(priceScore),
                                  'LIMIT', '0', '1')

    if #candidates == 0 then
        -- Sem contraparte: insere o BID no livro de ofertas
        redis.call('ZADD', KEYS[2], priceScore, orderValue)
        -- AT-04.2: popula o índice reverso para permitir remoção O(1) posterior
        redis.call('HSET', KEYS[3], splitPipe(orderValue)[1],
                   KEYS[2] .. '|' .. tostring(priceScore) .. '|' .. orderValue)
        return {'NO_MATCH'}
    end

    local askValue = candidates[1]
    local askScore = tonumber(redis.call('ZSCORE', KEYS[1], askValue))
    local parts    = splitPipe(askValue)
    local askQty   = tonumber(parts[4])

    if askQty > orderQty then
        -- Fill parcial: ASK ainda tem quantidade residual
        local remaining   = askQty - orderQty
        parts[4]          = string.format('%.8f', remaining)
        local newAskValue = table.concat(parts, '|')
        redis.call('ZREM', KEYS[1], askValue)
        redis.call('ZADD', KEYS[1], askScore, newAskValue)
        -- AT-04.2: atualiza índice com o novo member residual (HSET sobrescreve, mesmo orderId)
        redis.call('HSET', KEYS[3], parts[1],
                   KEYS[1] .. '|' .. tostring(askScore) .. '|' .. newAskValue)
        -- remainingCounterpartQty = qty remanescente do ASK (contraparte parcialmente executada)
        return {'MATCH', askValue, string.format('%.8f', orderQty), 'PARTIAL_ASK', string.format('%.8f', remaining)}

    elseif askQty == orderQty then
        -- Fill completo: remove o ASK inteiramente
        redis.call('ZREM', KEYS[1], askValue)
        -- AT-04.2: remove do índice — ASK totalmente consumido, não está mais no livro
        redis.call('HDEL', KEYS[3], parts[1])
        -- remainingCounterpartQty = 0 (ASK totalmente consumido)
        return {'MATCH', askValue, string.format('%.8f', orderQty), 'FULL', '0.00000000'}

    else
        -- ASK qty < BID qty: ASK é consumido; BID residual entra no livro
        redis.call('ZREM', KEYS[1], askValue)
        -- AT-04.2: ASK totalmente consumido — remove do índice
        redis.call('HDEL', KEYS[3], parts[1])
        local remainingBidQty = orderQty - askQty
        local bidParts        = splitPipe(orderValue)
        bidParts[4]           = string.format('%.8f', remainingBidQty)
        local newBidValue     = table.concat(bidParts, '|')
        redis.call('ZADD', KEYS[2], priceScore, newBidValue)
        -- AT-04.2: insere residual do BID (ingressante) no índice
        redis.call('HSET', KEYS[3], bidParts[1],
                   KEYS[2] .. '|' .. tostring(priceScore) .. '|' .. newBidValue)
        -- remainingCounterpartQty = 0 (ASK totalmente consumido; BID residual é a ordem INGRESSANTE)
        return {'MATCH', askValue, string.format('%.8f', askQty), 'PARTIAL_BID', '0.00000000'}
    end

elseif orderType == 'SELL' then
    -- Encontra o BID mais alto com preço >= preço do ASK
    -- BIDs armazenados com score = price → ZREVRANGEBYSCORE traz o mais alto primeiro
    local candidates = redis.call('ZREVRANGEBYSCORE', KEYS[2], '+inf', tostring(priceScore),
                                  'LIMIT', '0', '1')

    if #candidates == 0 then
        -- Sem contraparte: insere o ASK no livro de ofertas
        redis.call('ZADD', KEYS[1], priceScore, orderValue)
        -- AT-04.2: popula o índice reverso para permitir remoção O(1) posterior
        redis.call('HSET', KEYS[3], splitPipe(orderValue)[1],
                   KEYS[1] .. '|' .. tostring(priceScore) .. '|' .. orderValue)
        return {'NO_MATCH'}
    end

    local bidValue = candidates[1]
    local bidScore = tonumber(redis.call('ZSCORE', KEYS[2], bidValue))
    local parts    = splitPipe(bidValue)
    local bidQty   = tonumber(parts[4])

    if bidQty > orderQty then
        -- Fill parcial: BID ainda tem quantidade residual
        local remaining   = bidQty - orderQty
        parts[4]          = string.format('%.8f', remaining)
        local newBidValue = table.concat(parts, '|')
        redis.call('ZREM', KEYS[2], bidValue)
        redis.call('ZADD', KEYS[2], bidScore, newBidValue)
        -- AT-04.2: atualiza índice com o novo member residual do BID (HSET sobrescreve)
        redis.call('HSET', KEYS[3], parts[1],
                   KEYS[2] .. '|' .. tostring(bidScore) .. '|' .. newBidValue)
        -- remainingCounterpartQty = qty remanescente do BID (contraparte parcialmente executada)
        return {'MATCH', bidValue, string.format('%.8f', orderQty), 'PARTIAL_BID', string.format('%.8f', remaining)}

    elseif bidQty == orderQty then
        -- Fill completo
        redis.call('ZREM', KEYS[2], bidValue)
        -- AT-04.2: remove do índice — BID totalmente consumido, não está mais no livro
        redis.call('HDEL', KEYS[3], parts[1])
        -- remainingCounterpartQty = 0 (BID totalmente consumido)
        return {'MATCH', bidValue, string.format('%.8f', orderQty), 'FULL', '0.00000000'}

    else
        -- BID qty < ASK qty: BID consumido; ASK residual entra no livro
        redis.call('ZREM', KEYS[2], bidValue)
        -- AT-04.2: BID totalmente consumido — remove do índice
        redis.call('HDEL', KEYS[3], parts[1])
        local remainingAskQty = orderQty - bidQty
        local askParts        = splitPipe(orderValue)
        askParts[4]           = string.format('%.8f', remainingAskQty)
        local newAskValue     = table.concat(askParts, '|')
        redis.call('ZADD', KEYS[1], priceScore, newAskValue)
        -- AT-04.2: insere residual do ASK (ingressante) no índice
        redis.call('HSET', KEYS[3], askParts[1],
                   KEYS[1] .. '|' .. tostring(priceScore) .. '|' .. newAskValue)
        -- remainingCounterpartQty = 0 (BID totalmente consumido; ASK residual é a ordem INGRESSANTE)
        return {'MATCH', bidValue, string.format('%.8f', bidQty), 'PARTIAL_ASK', '0.00000000'}
    end
end

return {'NO_MATCH'}
