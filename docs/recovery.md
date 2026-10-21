# Backup e recuperação operacional

## Executar o ensaio isolado

Requer Docker Compose, Node.js e Postman CLI, conforme o [guia Postman](postman-testing.md).
Na raiz do repositório, em PowerShell 5.1 ou shell Linux:

```text
node scripts/postman/run.mjs --recovery
```

O runner constrói a imagem e cria um projeto `banking-postman-<aleatório>`.
Para reutilizar uma imagem, acrescente `--image sha256:<ID local completo>`.
Não há opção de URL, banco ou volume externo. O PostgreSQL deve usar tmpfs,
o projeto deve ter o prefixo esperado e a API deve publicar somente em loopback.
O único bind permitido pelo runner é o provisionamento em modo somente leitura.
Senhas e JWT são aleatórios; dados financeiros e credenciais não entram no relatório.

## O que é verificado

1. A collection executa 185 requisições e 1047 assertions nos 18 endpoints.
2. Um cliente novo deposita 100 com chave idempotente. O cliente descarta o corpo
   da resposta após receber os headers; SQL confirma saldo, uma transação e uma chave.
3. A API reinicia. A porta atual é consultada no Compose e o health precisa responder
   `UP` nela. O replay preserva o saldo e as contagens, sem duplicação.
4. O banco é pausado, uma tentativa de health confirma indisponibilidade e o banco
   é despausado em `finally`. Após recuperação, novo replay preserva o estado.
   Não se reinicia o PostgreSQL em tmpfs, pois isso perderia os dados do ensaio.
5. `pg_dump --format=custom` produz um snapshot consistente. `CREATE DATABASE`,
   sem `IF NOT EXISTS`, cria um nome aleatório novo; nomes externos e bancos existentes
   são recusados. `pg_restore --exit-on-error` restaura sem `--clean` nem sobrescrita.
6. Contagens e hashes ordenados de todas as linhas são comparados em `customers`,
   `addresses`, `accounts`, `transactions`, `financial_idempotency` e
   `flyway_schema_history`. O hash é usado para detectar diferenças, não como assinatura.
7. Privilégios administrativos, CREATE/TEMP e acesso ao histórico Flyway são negados
   ao runtime. Uma tentativa real de CREATE deve retornar SQLSTATE 42501; SELECT FOR
   UPDATE e UPDATE dentro de transação com ROLLBACK verificam DML e locks.
8. Outra API inicia contra o banco restaurado, com Flyway desativado e sem credenciais
   do migrador. Login, consulta de saldo e replay HTTP precisam funcionar, mantendo
   os snapshots. Essa configuração é exclusiva do ensaio, não altera o Compose normal.

O dump preserva proprietários e ACLs de objetos. Papéis existem previamente no mesmo
cluster isolado; os privilégios do banco novo e a configuração do migrador são
reaplicados explicitamente. O ensaio não testa restauração entre versões principais
do PostgreSQL nem provisionamento automático de outro cluster de produção.

## Evidências e limpeza

JUnit e Markdown ficam em `target/postman/<projeto>/` e `target/postman/latest/`.
As sete verificações operacionais atuais são casos JUnit adicionais, separados das 185
requisições da collection; não são contabilizadas como assertions Postman.
Falhas de comandos, descoberta de porta ou conectividade são infraestrutura.
Uma resposta HTTP inesperada ou divergência de estado é uma falha de verificação
da aplicação, que exige diagnóstico e não prova, por si só, defeito de negócio.
Nenhuma mensagem nativa, corpo HTTP ou erro SQL é publicado.

O `finally` remove a API de restauração, o dump, containers, rede, volumes e arquivos
privados do projeto. Qualquer falha de limpeza reprova a execução. Imagens emprestadas
e cache do scanner permanecem disponíveis. Encerramento forçado do host ou SIGKILL
não permite garantir execução de `finally`; nesse caso, inspecione os labels do
projeto registrado e remova somente seus recursos, nunca use prune global.

Teste intencional da limpeza em falha:

```text
node scripts/postman/run.mjs --simulate-failure
```

O resultado esperado é código 1, JUnit com erro e limpeza confirmada. `latest` passa
a representar esse ensaio negativo; o relatório anterior continua na pasta do projeto.

Em 16/10/2026, o ensaio positivo comparou 4 clientes, 4 contas, 12 transações,
6 registros idempotentes e 5 entradas Flyway; a tabela de endereços estava vazia.
As seis verificações e a limpeza passaram. O descarte de resposta não simula uma
queda de rede antes dos headers, e a pausa curta não substitui teste prolongado de
failover, corrupção de disco, desastre regional ou concorrência durante restauração.

## Procedimento operacional e rollback

A Etapa 4.1 acrescenta a verificação de liveness UP e readiness DOWN/503 durante
a pausa real do banco, seguida de readiness UP após recuperação. O registro de
16/10 acima continua sendo histórico de seis verificações. Veja [observabilidade](observability.md).

Este script é um ensaio descartável, não uma ferramenta de restauração de produção.
Para um ambiente real, planeje uma janela e um procedimento revisado:

1. Defina RPO, RTO, retenção, criptografia e acesso ao backup; guarde cópias fora do
   servidor. O dump contém dados pessoais, hashes de senha e histórico financeiro.
2. Faça `pg_dump` consistente e preserve separadamente o provisionamento de roles,
   ACLs, extensões e configurações de banco. Não publique `pg_dumpall --globals-only`,
   pois pode conter verificadores de senha. Proteja e confira checksums dos arquivos.
3. Restaure em banco/cluster novo, com versão compatível, roles previamente criadas
   e nenhuma conexão de aplicação. Não use `--clean` sobre o banco existente.
4. Confira dados, idempotência, histórico/checksums Flyway, ownership e permissões;
   execute smoke com usuário restrito antes de liberar tráfego.
5. Para trocar o datasource, interrompa escritas e reconcilie operações posteriores
   ao snapshot. Mantenha o banco anterior intacto. Não redirecione tráfego para uma
   cópia antiga que perderia movimentações confirmadas.
6. Rollback antes da liberação de escritas: retorne à configuração e imagem anteriores.
   Depois de novas escritas, reconciliação financeira é obrigatória; trocar simplesmente
   de banco ou restaurar um dump antigo não constitui rollback seguro.

Migrations antigas não são revertidas automaticamente. Prefira correção progressiva
revisada; nunca apague o histórico Flyway para contornar incompatibilidade.

Referências: [pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html),
[pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html) e
[papéis e volumes existentes](postgresql-security.md).

## Flyway separado do runtime

A Fase 4.3 utiliza `migrate`, um processo temporário na mesma imagem da API, sem
contexto Spring. O Compose principal tem quatro serviços (com Nginx); o ambiente
Postman tem três. A API depende de `service_completed_successfully`, usa somente
a identidade DML e tem `SPRING_FLYWAY_ENABLED=false`.

O ensaio de recuperação inicia a API da cópia restaurada com `--no-deps`, sem
reaplicar migrations e sem variáveis DDL. Primeiro restaure e valide com a mesma
versão. Atualizar o schema restaurado requer outra etapa explícita, com backup,
checagem de histórico e janela operacional. Consulte [deploy, migrations e rollback](migrations.md).
Nenhum volume existente é adaptado automaticamente por essa separação.
