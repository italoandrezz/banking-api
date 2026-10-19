# Docker Compose

Requer Docker Engine/Desktop com containers Linux e Compose v2. Execute os comandos no diretório de `compose.yaml` e `pom.xml`.

## Banco novo

Copie `.env.example` para `.env` e preencha quatro segredos distintos: `DB_ADMIN_PASSWORD`, `FLYWAY_PASSWORD`, `DB_PASSWORD` e `JWT_SECRET`. O segredo JWT deve ter ao menos 32 caracteres ASCII aleatórios. Não sobrescreva um `.env` existente.

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
| `DB_ADMIN_PASSWORD` | Senha do administrador | Obrigatória |
| `FLYWAY_USERNAME` | Migrador e proprietário dos objetos | `banking_migrator` |
| `FLYWAY_PASSWORD` | Senha do migrador | Obrigatória |
| `DB_USERNAME` | Aplicação | `banking_app` |
| `DB_PASSWORD` | Senha da aplicação | Obrigatória |
| `JWT_SECRET` | Assinatura JWT | Obrigatória |
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

Provisione os usuários e permissões antes de iniciar o Java. Configure `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `FLYWAY_USERNAME`, `FLYWAY_PASSWORD` e `JWT_SECRET`. O Spring Boot não carrega `.env` automaticamente. Nunca execute com o administrador como datasource runtime. O profile `test` usa configurações próprias de fixtures e não deve ser ativado em produção.
