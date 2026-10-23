# Segurança e privilégios PostgreSQL

## Identidades e limites

| Identidade | Finalidade | Privilégios |
|---|---|---|
| `DB_ADMIN_USERNAME` (padrão `banking_admin`) | Inicialização e administração do cluster | Superusuário criado pela imagem oficial; não é enviado ao container da API |
| `FLYWAY_USERNAME` (padrão `banking_migrator`) | Migrations e callback de permissões | CONNECT, propriedade do schema `public` e dos objetos criados pelo Flyway; sem SUPERUSER, CREATEDB, CREATEROLE, REPLICATION ou BYPASSRLS |
| `DB_USERNAME` (padrão `banking_app`) | Datasource JPA/Hibernate | CONNECT, USAGE no schema e enums; SELECT, INSERT, UPDATE, DELETE em `customers`, `addresses`, `accounts`, `transactions` e `financial_idempotency` |

A aplicação não é proprietária de objetos, não herda o migrador, não acessa
`flyway_schema_history` e não recebe CREATE, TEMP, TRUNCATE, REFERENCES ou TRIGGER.
As entidades utilizam UUID: nenhuma sequence é necessária atualmente.
O Hibernate permanece em `ddl-auto=validate`. O Flyway usa conexão própria,
com `clean` desabilitado. As migrations V1 a V5 permanecem inalteradas.

O administrador mantém acesso administrativo, inclusive aos dados. As credenciais
DDL ficam no bootstrap do banco e no serviço temporário `migrate`, nunca na API.
O [procedimento de migrations](migrations.md) descreve inicialização, atualização
de volumes existentes e rollback.

## Banco novo

Configure `SECRETS_DIR` no `.env` e prepare senhas distintas nos arquivos privados
descritos no [guia de secrets](secrets.md). O Compose não recebe seus valores no ambiente.
Nomes dos três usuários devem ser distintos e seguir `[a-z][a-z0-9_]{0,62}`.
O nome do banco é definido por `DB_NAME`.

```powershell
docker compose config --quiet
docker compose up -d --build --wait
```

Esses comandos pressupõem um projeto com volume novo. O entrypoint oficial só
executa `docker/postgres/10-roles.sh` quando o diretório de dados está vazio.
O script, montado somente para leitura, cria as identidades e revoga os privilégios
de PUBLIC no banco/schema. O migrador aguarda o banco saudável; a API aguarda sua saída zero.
Reiniciar um volume existente não reprovisiona roles nem altera suas senhas.

Sem Compose, o administrador deve provisionar previamente o banco e os papéis.
Configure `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` e JWT somente
na API. Injete `MIGRATION_DATABASE_URL`, `FLYWAY_USERNAME` e `FLYWAY_PASSWORD`
em outro processo, exclusivo para o `MigrationRunner`.
O migrador precisa ter a configuração por banco `banking.application_role`
apontando para o usuário runtime; ela é criada pelo provisionamento e utilizada
pelo callback `src/main/resources/db/callback/afterMigrate.sql`.

## Migrations e objetos futuros

O serviço `migrate` valida o histórico, aplica migrations pendentes e executa o
callback transacional de permissões. Só depois a API inicia, com Hibernate
validando o schema e utilizando apenas o datasource runtime. Reiniciar a API
não reaplica migrations: cada deploy deve executar um migrador novo da mesma imagem.

O callback concede acesso apenas à lista explícita de tabelas de negócio.
Não há GRANT automático em todas as tabelas futuras: isso exporia tabelas internas,
incluindo o histórico Flyway. Uma migration que introduza uma tabela usada pela
API deve atualizar também a lista do callback. Para sequences necessárias, conceda
somente USAGE às sequences específicas no callback. UPDATE em sequences não é
necessário para `nextval`. Funções futuras não recebem EXECUTE para PUBLIC;
tipos futuros também exigem concessão explícita. Views e funções SECURITY DEFINER
exigem revisão de segurança antes de serem disponibilizadas.

## Verificação dos privilégios efetivos

Use uma sessão autenticada como aplicação, sem registrar sua senha:

```sql
SELECT current_user, session_user;
SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname = current_user;
SELECT has_schema_privilege(current_user, 'public', 'CREATE');
SELECT has_database_privilege(current_user, current_database(), 'CREATE,TEMP');
SELECT has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT');
SELECT table_name, privilege_type FROM information_schema.role_table_grants
WHERE grantee = current_user ORDER BY table_name, privilege_type;
```

Os atributos administrativos e as três verificações de acesso acima devem ser
falsos. Apenas as cinco tabelas autorizadas devem apresentar DML. Como migrador,
confira `installed_by`, `version`, `checksum` e `success` no histórico Flyway.
Execute testes de comandos proibidos somente em banco descartável.

## Volume existente: procedimento operacional, não automático

**Este procedimento exige autorização específica do responsável pelo banco. Não
foi executado no banco de desenvolvimento.** Não execute o script de inicialização
em um volume existente nem use `down --volumes`, `DROP`, `TRUNCATE`, Flyway `clean`
ou `repair` como forma de adaptação.

1. Agende manutenção, suspenda escritas e identifique banco, volume, administrador,
   proprietários, ACLs, memberships e versões aplicadas. Guarde a configuração antiga
   em local privado. O antigo `DB_USERNAME` pode ser superusuário e já ocupar o nome
   desejado para a aplicação: nesse caso escolha um novo nome para o runtime e
   mantenha o antigo somente como `DB_ADMIN_USERNAME`.
2. Faça backup consistente com `pg_dump --format=custom` e backup das identidades
   e ACLs globais com `pg_dumpall --globals-only`. Use os binários do container ou
   cliente compatível com o servidor, grave em arquivos privados e copie os backups
   para armazenamento protegido. Não use redirecionamento binário do PowerShell 5.1
   para o dump: utilize `pg_dump --file` e depois `docker cp`, se necessário.
   O dump de globals contém verificadores de senhas: nunca publique no Git/artifacts.
3. Confirme os arquivos com `pg_restore --list` e restaure os backups em **outro
   cluster descartável**, isolado. Valide contagens, saldos, transações, constraints
   e histórico/checksums. Não avance sem restauração testada.
4. No clone, prepare SQL administrativo específico para criar os dois novos papéis
   com LOGIN e atributos administrativos desabilitados, sem memberships. Use senhas
   de um gerenciador, não literais em scripts versionados. Revogue PUBLIC no banco e
   schema, conceda CONNECT, USAGE no schema ao runtime e transfira o schema `public`
   ao migrador.
5. Transfira **somente os objetos desta aplicação** ao migrador: as cinco tabelas,
   `flyway_schema_history` e os enums `account_status`/`transaction_type`. Inspecione
   sequences, views e funções adicionais antes de incluí-las. Use ALTER TABLE/TYPE
   ... OWNER TO para cada objeto revisado. Não use REASSIGN OWNED indiscriminadamente:
   o usuário antigo pode ser proprietário de objetos alheios à API.
6. Configure `search_path=public` para os dois usuários e, para o migrador naquele
   banco, `banking.application_role` com o novo runtime. Aplique as mesmas revogações
   de privilégios padrão de funções/tipos usadas no provisionamento novo. Revogue
   grants/memberships anteriores do novo runtime, caso ele não seja recém-criado.
7. Atualize as variáveis separadas no clone e inicie a versão nova. Flyway deve
   validar o histórico existente e executar o callback, sem baseline ou repair.
   Confira DML, permissões negadas, proprietários, `installed_by` histórico e dados.
   Registros antigos mantêm o autor original; novas migrations usam o migrador.
8. Somente após aprovação do ensaio e autorização específica, repita a operação
   revisada na instalação real, mantendo escritas suspensas até o smoke passar.
   Não altere nomes de volume/projeto de forma que a aplicação use outro banco
   inadvertidamente. Não rotacione a senha do administrador sem procedimento próprio.

### Rollback

Mantenha a versão anterior, os valores de configuração e um script revisado que
restaure os proprietários/ACLs/configurações originais. Se a alteração foi apenas
de permissões, reverta esses metadados durante a manutenção, sem tocar nos dados.
Voltar ao usuário administrativo no runtime é uma contingência temporária insegura.
Se houver alteração de schema incompatível, restaure o backup testado em **novo
volume**, valide-o e faça a troca controlada; preserve o volume original. Depois
que escritas forem retomadas, não restaure um backup antigo sem reconciliar os
dados posteriores e obter autorização. Nunca faça rollback automático destrutivo.

## Testes e diagnóstico

`mvnw clean verify` agora exige Docker para `PostgresPrivilegesTest`. Esse teste cria
seu próprio PostgreSQL 17 em tmpfs, autentica os três usuários por TCP, verifica
negações reais, constraints, locks/rollback, objetos futuros e upgrade V4 → V5 com
dados e histórico preservados. Os 299 testes anteriores continuam usando seus
schemas descartáveis com permissões administrativas para fixtures/TRUNCATE;
isso não representa a configuração de produção.

O runner Postman usa três senhas aleatórias, inspeciona os privilégios via SQL e
executa a collection com o datasource restrito. Só permite o bind de código de
provisionamento em modo leitura, além de tmpfs; dados persistentes continuam
proibidos. Os checks obrigatórios do CI permanecem `build` e
`Docker Compose validation`.

- `role does not exist` em volume antigo: o init não é repetido; use o procedimento
  operacional, sem remover o volume.
- Erro em `banking.application_role`: falta provisionamento/configuração do migrador.
- Erro de propriedade em migrations: confira os donos dos objetos, não conceda
  SUPERUSER à aplicação ou ao Flyway.
- Tabela nova inacessível: revise o GRANT específico no callback.
- Segredos novos no `.env` não atualizam automaticamente senhas de roles existentes.

Referências: [datasource Flyway no Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html),
[privilégios PostgreSQL](https://www.postgresql.org/docs/17/sql-grant.html) e
[privilégios padrão](https://www.postgresql.org/docs/17/sql-alterdefaultprivileges.html).
