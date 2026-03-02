--[[
  Remoção O(1) de ordem do Order Book via índice reverso.

  Substitui o ZSCAN (O(n)) do RemoveFromBook original por um fluxo de 3 comandos:
    HGET  → localiza bookKey e member em O(1)
    ZREM  → remove o member do Sorted Set em O(log N)
    HDEL  → remove a entrada do índice em O(1)

  Todo o script executa atomicamente via EVAL no Redis, eliminando
  race conditions entre remoção do índice e remoção do Sorted Set.

  Compatibilidade com Redis Cluster (AT-11.1):
  Todas as keys acessadas usam a hash tag {vibranium}, garantindo
  que asks, bids e order_index calculem o mesmo slot CRC16.
  O Cluster valida as keys declaradas em KEYS[] antes de executar.

  KEYS:
    KEYS[1] = order_index_key  (ex: "{vibranium}:order_index")
    KEYS[2] = asks_key         (ex: "{vibranium}:asks")
    KEYS[3] = bids_key         (ex: "{vibranium}:bids")
    Nota: KEYS[2] e KEYS[3] são declaradas para conformidade com Redis Cluster.
    O bookKey consultado dinamicamente via HGET sempre será um desses dois valores —
    portanto, o Cluster pode validar que todos os acessos estão no mesmo hash slot.

  ARGV:
    ARGV[1] = orderId  (UUID string)

  Retorno:
     1  → removido com sucesso (ZREM + HDEL OK)
     0  → orderId não encontrado no índice (idempotente — ordem não indexada ou já removida)
    -1  → entrada do índice corrompida (HDEL executado para limpar; ZREM ignorado)
]]

-- Consulta o índice reverso para obter a chave e o member completo do Sorted Set.
-- O(1): HGET em hash Redis.
local entry = redis.call('HGET', KEYS[1], ARGV[1])

if not entry or entry == false then
    -- orderId ausente do índice: foi removido anteriormente ou nunca foi indexado.
    -- Comportamento idempotente: retorna 0 sem falhar.
    return 0
end

-- Extrai bookKey (tudo antes do 1º '|') e member (tudo após o 2º '|').
-- Formato armazenado: "{vibranium}:asks|<score>|<orderId>|userId|walletId|qty|correlId|epochMs"
-- string.find(entry, '|', pos, plain=true) localiza o delimitador sem regex.
local sep1 = string.find(entry, '|', 1, true)
if not sep1 then
    -- Entrada corrompida: sem delimitador. Remove do índice para limpar o estado.
    redis.call('HDEL', KEYS[1], ARGV[1])
    return -1
end

local sep2 = string.find(entry, '|', sep1 + 1, true)
if not sep2 then
    -- Entrada corrompida: segundo delimitador ausente.
    redis.call('HDEL', KEYS[1], ARGV[1])
    return -1
end

-- bookKey = texto antes do 1º '|' → "{vibranium}:asks" ou "{vibranium}:bids"
local bookKey = string.sub(entry, 1, sep1 - 1)

-- member = texto após o 2º '|' → valor pipe-delimited completo do Sorted Set
-- (o score — entre sep1 e sep2 — não é necessário para ZREM)
local member = string.sub(entry, sep2 + 1)

-- Remoção atômica: ZREM do Sorted Set + HDEL do índice no mesmo script Lua.
-- ZREM é O(log N) — melhor que O(n) do ZSCAN.
-- Redis retorna o número de membros removidos (1 se encontrado, 0 se ausente).
redis.call('ZREM', bookKey, member)
redis.call('HDEL', KEYS[1], ARGV[1])

return 1
