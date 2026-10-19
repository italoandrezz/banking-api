# Observabilidade segura

## Saúde e disponibilidade

Somente estes endpoints Actuator aceitam GET sem autenticação:

| Endpoint | Uso | Dependências |
|---|---|---|
| `/actuator/health` | Saúde agregada, preservada para o Compose | Inclui o banco |
| `/actuator/health/liveness` | O processo está vivo? | Apenas `livenessState`, sem PostgreSQL |
| `/actuator/health/readiness` | Pode atender tráfego? | `readinessState` e `db` |

Readiness retorna 503 quando o indicador do banco está DOWN; liveness continua UP
durante a indisponibilidade do banco. Nenhuma resposta de saúde publica componentes,
credenciais ou detalhes de exceções. As probes compartilham a porta da API.
O health agregado inclui também `groups: ["liveness", "readiness"]`, metadado nativo
do Actuator; cada probe individual responde somente com `status`.
Uma falha de readiness deve retirar a instância do tráfego, não reiniciá-la em ciclo.
Não use a saúde agregada como liveness de um orquestrador. O healthcheck Docker
existente permanece agregado; Docker Compose não reinicia automaticamente um
container apenas por estar unhealthy.

Todos os demais caminhos `/actuator/**` são negados, inclusive para ADMIN.
Métricas, env, heapdump, loggers e Prometheus não são expostos. JMX está desabilitado.
As regras dos endpoints bancários e administrativos permanecem inalteradas.

## Correlação

`X-Correlation-ID` aceita exatamente um UUID textual de 36 caracteres, com hífens.
Ausência, duplicidade, caracteres inválidos ou tamanho diferente geram um novo UUID.
O valor inválido não é registrado. O ID é devolvido no header, inclusive em 401,
403 e falhas internas que atravessam a cadeia servlet. Rejeições anteriores à cadeia
(por exemplo, header recusado pelo Tomcat) não têm essa garantia.

O ID fica no MDC durante a execução síncrona da requisição e nos dispatches ERROR.
O filtro restaura o contexto anterior em `finally`, inclusive em exceções, impedindo
contaminação entre threads/requisições. Não há propagação para tarefas assíncronas
arbitrárias; os endpoints atuais são síncronos. O ID não autentica o cliente, não
é chave de idempotência e não deve conter identificadores pessoais, mesmo em UUID.

## Logs JSON e privacidade

O console e o appender de arquivo, se habilitado pelo operador, usam a extensão
nativa `StructuredLogFormatter` do Spring Boot. Não há dependência adicional.
`SafeJsonLogFormatter` serializa exclusivamente:

- `timestamp` em UTC, `level` e nome técnico de `logger`;
- `event`: `http_request_completed` ou `technical_event`;
- `correlationId`, validado novamente na saída;
- `status` e `durationMs` numéricos nos eventos de conclusão HTTP;
- `exceptionType`, sem a mensagem da exceção.

Mensagens livres, argumentos de logging, pares adicionais SLF4J, MDC arbitrário,
stack traces, headers, URLs, query strings e corpos não são exportados. Isso evita
que erros JDBC/Hibernate publiquem CPF, e-mail, hashes de senha ou valores financeiros.
O SQL direto do Hibernate está desabilitado; `SHOW_SQL` deixou de ser uma opção
suportada pela configuração do projeto. Não habilite SQL, bind parameters, access
logs com dados pessoais ou appenders que contornem o formatter.

A restrição também reduz o detalhe de mensagens de inicialização e de falhas.
Use nível, logger, tipo de exceção, status, probes e correlação para o diagnóstico.
Se precisar de detalhe adicional, reproduza em ambiente isolado com dados sintéticos;
não habilite mensagens/stack traces brutos em um ambiente com dados reais.
Saídas diretas de ferramentas externas, código com `System.out` e configuração
externa que substitua o logging não são sanitizadas por este formatter.

O resumo HTTP mede tempo no filtro, não a latência completa da rede. Um erro que
gera dispatch ERROR pode produzir um segundo evento com o mesmo ID; não use esse
log para contar operações financeiras ou requisições únicas. Para taxa e latência,
use o timer HTTP do Micrometer. A duração nunca representa valor financeiro.

## Métricas e indicadores

Actuator/Micrometer já coletam métricas no processo. Esta etapa valida a presença de
`http.server.requests`, `jvm.memory.used` e `hikaricp.connections.active` por
`MeterRegistry`, sem criar um endpoint público de diagnóstico.

| Indicador | Interpretação |
|---|---|
| Status HTTP e taxa de 5xx | Falhas técnicas; distinguir de 4xx esperados |
| Timer `http.server.requests` | Contagem e duração HTTP, por tags de baixa cardinalidade |
| JVM: memória e GC | Pressão de heap e pausas |
| Hikari: conexões ativas, ociosas e pendentes | Saturação do pool e contenção |
| Readiness/liveness | Dependência indisponível versus estado do processo |

Correlation IDs, CPF, clientes, contas, saldos e tokens não são tags de métricas.
Não há Prometheus, Grafana, dashboard, exportação remota ou alerta automático nesta
etapa. Limiares de alerta dependem de carga e SLOs ainda a definir.

## Diagnóstico básico

1. Consulte liveness e readiness no endereço local da instância.
2. Se apenas readiness falhar, investigue conectividade e disponibilidade do banco;
   não use comandos que removam volumes.
3. Para um erro HTTP, retenha somente horário, status e `X-Correlation-ID` para
   localizar o evento JSON. Não cole Authorization nem corpos em chamados.
4. Avalie recorrência de ERROR/WARN e tipo de exceção; valide a configuração e
   consulte os procedimentos PostgreSQL e de recuperação.
5. Após recuperação, confirme readiness UP e valide a operação com a mesma chave
   idempotente quando aplicável; não crie outra movimentação para testar o saldo.

Exemplos somente de leitura, ajustando a porta local:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
docker compose logs --tail=100 api
```

## Validação isolada

Os testes Java `CorrelationIdFilterTest`, `SafeJsonLogFormatterTest` e
`ObservabilityHttpTest` cobrem IDs, concorrência, MDC, JSON, privacidade, erros,
probes, métricas e negação de endpoints Actuator. Execute `mvnw clean verify`
somente com PostgreSQL reservado para testes, conforme o README.

`node scripts/postman/run.mjs --recovery` executa a collection e sete verificações
operacionais. A nova verificação pausa exclusivamente o PostgreSQL descartável:
liveness deve responder 200, readiness 503 e, após despausar, readiness 200.
Timeout ou erro de conexão HTTP não substituem a resposta 503 esperada.
O Compose Postman limita conexão/socket JDBC e aquisição Hikari somente no ensaio,
para tornar a falha determinística; os timeouts operacionais da aplicação normal
serão tratados na etapa de resiliência. Limpeza continua obrigatória.

Validação local em 19/10/2026: Maven `clean verify` aprovou 316 testes (302 existentes
e 14 novos), Node.js aprovou 41 testes, Postman aprovou 185 requisições e 1047
assertions, recuperação aprovou sete verificações e o Compose principal aprovou
15 requisições de smoke. JUnit Postman contém 192 casos, sem falhas ou erros.
Todos os bancos usados foram descartáveis e a limpeza foi confirmada. As assertions
antigas de health foram atualizadas para exigir exatamente os novos grupos públicos,
mantendo a rejeição de detalhes internos. Estes resultados são locais; GitHub Actions
ainda precisa validar a branch após autorização para publicação.
