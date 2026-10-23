# Docker Compose

Requer Docker Engine/Desktop com containers Linux e Compose v2. Execute os comandos no diretório de `compose.yaml` e `pom.xml`.

## Banco novo

Configure `SECRETS_DIR` no `.env` e prepare os arquivos privados `admin_password`, `app_password`, `migrator_password` e `jwt_secret` conforme o [guia de secrets](secrets.md). Não sobrescreva um `.env` existente. O JWT deve ter pelo menos 32 bytes de texto UTF-8 com entropia adequada.

```powershell
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
```

Somente o Nginx publica em loopback; API e PostgreSQL não publicam portas no host. Consulte os [limites de login e cadastro](auth-rate-limiting.md), a topologia e os testes isolados. O banco usa três identidades, descritas no [guia PostgreSQL](postgresql-security.md). O Flyway executa migrations com o migrador; JPA usa a aplicação sem privilégios DDL.

| Variável Compose | Finalidade | Padrão |
|---|---|---|
| `DB_NAME` | Banco inicial | `banking_db` |
| `DB_ADMIN_USERNAME` | Administrador inicial, exclusivo do serviço db | `banking_admin` |
| `SECRETS_DIR` | Diretório privado dos quatro arquivos de secrets | Obrigatório |
| `FLYWAY_USERNAME` | Migrador e proprietário dos objetos | `banking_migrator` |
| `DB_USERNAME` | Aplicação | `banking_app` |
| `JWT_EXPIRATION` | Duração em ms, até 86400000 | `86400000` |
| `API_PORT` | Porta do Nginx no host | `8080` |

O script em `docker/postgres` é montado somente para leitura. O entrypoint oficial executa o provisionamento apenas no volume vazio. Não há reprovisionamento automático em volumes existentes.

## Persistência e instalações existentes

```powershell
docker compose down
docker compose up -d --wait
```

Esses comandos preservam o volume `postgres_data`. Não use `down --volumes` em bancos que precisam ser preservados. Alterar as variáveis no `.env` não altera senhas/roles de um PostgreSQL já inicializado. Antes de usar a configuração nova com volume antigo, siga o procedimento de backup, ensaio, adaptação e rollback no [guia PostgreSQL](postgresql-security.md). Nenhuma adaptação administrativa é executada automaticamente.

## Validação

```powershell
.\scripts\Test-Compose.ps1 -BaseUrl http://localhost:8080
.\scripts\Test-Postman.ps1
```

O primeiro script cria dados no ambiente selecionado; use um projeto descartável para testes. O segundo cria seu próprio PostgreSQL em tmpfs, verifica permissões e executa 185 requisições/1047 assertions, removendo seus recursos no final. Não utiliza o banco de desenvolvimento.

A suíte `mvnw clean verify` exige Java 17, um PostgreSQL dedicado aos testes anteriores e Docker para os três novos testes de privilégios/upgrade. Consulte [CI](ci.md) e [Postman](postman-testing.md).

## Sem Docker

Provisione os usuários e permissões e execute o [migrador separado](migrations.md) antes de iniciar a API. Configure somente `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` e `JWT_SECRET` no runtime. O Spring Boot não carrega `.env` automaticamente. Nunca execute com o administrador como datasource runtime. O profile `test` usa configurações próprias de fixtures e não deve ser ativado em produção.

## Serviço temporário de migrations

O Compose usa quatro serviços: `db`, `migrate`, `api` e `nginx`. A API depende da
saída zero do migrador, com a mesma imagem e sem herdar suas credenciais.
Para atualizar volumes existentes, consulte o [procedimento de deploy e rollback](migrations.md).
Um simples restart da API não executa migrations. O serviço `migrate` não publica
portas, não monta o volume de dados e só participa da rede interna `database`.
