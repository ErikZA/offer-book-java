package com.vibranium.contracts;

/**
 * Contrato de versionamento para todos os Eventos e Comandos da plataforma Vibranium.
 *
 * <p>Garante que produtor e consumidor possam evoluir de forma independente
 * ({@code deploy} sem coordenação), respeitando os princípios de:</p>
 * <ul>
 *   <li><strong>Backward compatibility</strong> — consumer novo aceita payload antigo
 *       (sem {@code schemaVersion}): assume versão 1 pelo compact constructor.</li>
 *   <li><strong>Forward compatibility</strong> — consumer antigo aceita payload novo
 *       (com campos extras): ignorado via {@code FAIL_ON_UNKNOWN_PROPERTIES=false}.</li>
 * </ul>
 *
 * <h2>Convenção de versões</h2>
 * <ul>
 *   <li>{@code 1} — versão inicial de todos os contratos.</li>
 *   <li>Incrementar quando um campo <strong>obrigatório</strong> for adicionado.</li>
 *   <li>Campos opcionais podem ser adicionados sem incrementar — o consumer
 *       simplesmente os ignora via {@code FAIL_ON_UNKNOWN_PROPERTIES=false}.</li>
 * </ul>
 *
 * <h2>Limitações</h2>
 * <p>O {@code schemaVersion} <strong>não resolve breaking changes</strong> como
 * remoção ou renomeação de campos. Nesses casos, é necessária uma estratégia
 * de versionamento de tópico/exchange ou período de coexistência de versões.</p>
 *
 * @see com.vibranium.contracts.events.DomainEvent
 * @see com.vibranium.contracts.commands.Command
 */
public interface VersionedContract {

    /**
     * Versão do esquema deste contrato.
     *
     * <p>Todo {@code record} que implementa esta interface deve declarar o campo
     * {@code int schemaVersion} e aplicar o seguinte compact constructor
     * para garantir o valor padrão durante a desserialização de payloads antigos:</p>
     * <pre>{@code
     * public MyEvent {
     *     if (schemaVersion == 0) schemaVersion = 1;
     * }
     * }</pre>
     *
     * @return versão do esquema; {@code 1} para todos os contratos atuais
     */
    int schemaVersion();
}
