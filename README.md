# Banking API

API REST em Java 17 e Spring Boot para simular operações bancárias. Projeto de estudo e portfólio com autenticação JWT, PostgreSQL e testes automatizados.

## Funcionalidades

- Cadastro, consulta, atualização e exclusão do próprio cliente.
- Criação e consulta de contas, bloqueio, desbloqueio e encerramento.
- Depósitos, saques e transferências com histórico e controle de concorrência.
- Extrato paginado com filtros por período e tipo de transação.
- Idempotência opcional nas movimentações financeiras.
- Estorno administrativo com registro do responsável e motivo.
- Validação de entradas e respostas de erro padronizadas.

## Tecnologias e arquitetura

Java 17, Spring Boot, Spring Web, Spring Data JPA, Spring Security, Bean Validation, PostgreSQL, Flyway, Springdoc OpenAPI, Maven e Docker Compose.

O código segue uma arquitetura em camadas: `controller` recebe as requisições, `service` aplica as regras de negócio e `repository` acessa o banco. DTOs definem os contratos de entrada e saída; entidades representam a persistência. As migrations ficam em `src/main/resources/db/migration`.

## Executar com Docker Compose

Requisitos: Git, Docker com containers Linux e Docker Compose v2.

```bash
git clone https://github.com/italoandrezz/banking-api.git
cd banking-api
git switch develop
```

Copie `.env.example` para `.env` e preencha `DB_PASSWORD` e `JWT_SECRET` com segredos distintos. Use um segredo JWT aleatório com pelo menos 32 caracteres ASCII. No PowerShell:

```powershell
Copy-Item .env.example .env
# Preencha os segredos no arquivo .env antes de iniciar.
docker compose up -d --build --wait
```

| Recurso | Endereço padrão |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| OpenAPI | http://localhost:8080/v3/api-docs |
| Saúde da aplicação | http://localhost:8080/actuator/health |

A API fica acessível apenas na máquina local; o PostgreSQL não publica uma porta no host. Altere `API_PORT` no `.env` se a porta 8080 estiver ocupada.

```powershell
.\scripts\Test-Compose.ps1
docker compose logs --tail=100 api
docker compose down
```

O script executa um fluxo HTTP e cria dados de exemplo. O volume do banco permanece após `docker compose down`; adicionar `--volumes` remove os dados. O `.env` é ignorado pelo Git. Configurações e detalhes de persistência estão no [guia de Docker](docs/docker.md).

### Executar sem Docker

Requisitos: JDK 17 e PostgreSQL com o banco `banking_db` criado. Configure as variáveis no ambiente do processo:

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/banking_db'
$env:DATABASE_USERNAME = 'postgres'
$env:DATABASE_PASSWORD = 'SUA_SENHA_DO_BANCO'
$env:JWT_SECRET = 'SUBSTITUA_POR_UM_SEGREDO_ALEATORIO_DE_32_OU_MAIS_CARACTERES'
.\mvnw.cmd spring-boot:run
```

No Linux/macOS, use `export` para as variáveis e `./mvnw spring-boot:run`. O Maven Wrapper dispensa instalação separada do Maven. O Spring Boot não lê o `.env` automaticamente nessa forma de execução.

Variáveis opcionais: `SERVER_PORT` (padrão `8080`), `JWT_EXPIRATION` em milissegundos (padrão `86400000`) e `SHOW_SQL` (padrão `false`). O Flyway aplica as migrations na inicialização.

## Autenticação e acesso

1. Cadastre um cliente em `POST /customers`.
2. Envie e-mail e senha para `POST /auth/login`.
3. Use o token recebido no cabeçalho `Authorization: Bearer <token>`.

Exemplo de cadastro:

```json
{
  "fullName": "Maria Silva",
  "cpf": "12345678901",
  "email": "maria@example.com",
  "password": "senha-de-exemplo",
  "phone": "81999999999",
  "birthDate": "2000-01-01"
}
```

O token identifica o UUID do cliente, que não muda ao alterar o e-mail. Cada cliente acessa apenas seus dados e contas; transferências podem ter outra pessoa como destinatária. Depósitos simulam uma operação do titular, não uma integração com pagamentos externos.

Cadastros recebem o papel `CUSTOMER`. O papel `ADMIN` permite estornos, mas não elimina as verificações de propriedade nos endpoints comuns. As permissões são consultadas no banco a cada requisição. Não existe administrador padrão nem promoção pública de usuários.

Um operador autorizado pode promover ou revogar um cliente existente pelo banco:

```sql
UPDATE customers SET role = 'ADMIN' WHERE id = 'UUID_DO_CLIENTE' AND role = 'CUSTOMER';
UPDATE customers SET role = 'CUSTOMER' WHERE id = 'UUID_DO_CLIENTE' AND role = 'ADMIN';
```

## Endpoints

Os contratos completos de entrada e saída estão no Swagger UI.

| Método | Rota | Finalidade |
|---|---|---|
| POST | `/customers` | Cadastrar cliente |
| POST | `/auth/login` | Obter token JWT |
| GET | `/auth/me` | Consultar identidade e papel atuais |
| GET | `/customers/{id}` | Consultar o próprio cadastro |
| PUT | `/customers/{id}` | Atualizar o próprio perfil |
| DELETE | `/customers/{id}` | Excluir cliente sem registros vinculados |
| POST | `/accounts` | Criar conta para o próprio `customerId` |
| GET | `/accounts` | Listar as próprias contas |
| GET | `/accounts/{id}` | Consultar conta |
| PATCH | `/accounts/{id}/block` | Bloquear conta |
| PATCH | `/accounts/{id}/unblock` | Desbloquear conta |
| PATCH | `/accounts/{id}/close` | Encerrar conta |
| POST | `/accounts/{id}/deposit` | Depositar |
| POST | `/accounts/{id}/withdraw` | Sacar |
| POST | `/accounts/transfer` | Transferir |
| GET | `/accounts/{id}/transactions` | Consultar extrato |
| POST | `/admin/transactions/{id}/reversal` | Estornar transação como administrador |

`GET /customers` não disponibiliza listagem: retorna `403` para usuários autenticados. Bloqueio, desbloqueio e encerramento não exigem corpo na requisição.

## Regras de uso

### Clientes e validação

- CPF exige 11 dígitos; CPF e e-mail são únicos. Duplicidades, inclusive concorrentes, retornam `409`.
- Nome aceita até 120 caracteres, e-mail até 150 e telefone até 20.
- Nome, e-mail, telefone e motivo de estorno rejeitam o caractere nulo (`U+0000`). Unicode válido permanece sujeito às regras de cada campo.
- Datas aceitam anos de `0001` a `9999`. Nascimento deve ser anterior ao dia atual; a mesma regra vale na atualização.
- Novas senhas exigem pelo menos 8 pontos de código Unicode e no máximo 72 bytes UTF-8. Não são cortadas ou normalizadas. O login preserva senhas curtas já existentes e rejeita entradas acima do limite de bytes.
- Atualizar o perfil não altera CPF, senha ou papel. Entre atualizações concorrentes dos campos de perfil, prevalece a última gravação.
- Excluir um cliente com registros vinculados retorna `409`. Exclusão concorrente durante a criação de conta retorna `404` quando a exclusão vence a inserção.

### Contas e movimentações

Somente contas `ACTIVE` podem movimentar valores, inclusive receber transferências. Uma conta `BLOCKED` pode ser desbloqueada; o encerramento exige conta ativa com saldo zero e resulta em `CLOSED`, sem possibilidade de reabertura. Consultas e histórico continuam disponíveis nos três estados.

Valores devem ser positivos, com até 13 dígitos inteiros e 2 casas decimais, limitados a `9999999999999.99`. Entradas com casas decimais excedentes são rejeitadas, não arredondadas. Saldo insuficiente ou crédito acima do limite retorna `409`.

Saldo e histórico são gravados na mesma transação: uma falha desfaz a operação inteira. Bloqueios no banco coordenam movimentações e mudanças de estado. A criação de conta tenta alocar um número até dez vezes em caso de colisão; ao esgotar as tentativas, retorna `409`.

### Extrato

Exemplo:

```http
GET /accounts/UUID_DA_CONTA/transactions?page=0&size=20&startDate=2026-10-01&endDate=2026-10-31&type=TRANSFER
Authorization: Bearer TOKEN
```

| Parâmetro | Padrão | Regra |
|---|---|---|
| `page` | `0` | Não negativo; `page * size` limitado a `2147483647` |
| `size` | `20` | De 1 a 100 |
| `startDate` | Sem filtro | Data inicial inclusiva |
| `endDate` | Sem filtro | Data final inclusiva, não anterior à inicial |
| `type` | Todos | `DEPOSIT`, `WITHDRAW` ou `TRANSFER` |

Os filtros são combináveis. As datas usam o calendário do timestamp armazenado, sem conversão de fuso. O extrato inclui transferências enviadas e recebidas, ordenadas por data e UUID decrescentes.

A resposta contém `content`, `page`, `size`, `totalElements` e `totalPages`. Páginas posteriores à última retornam `content` vazio. Novas movimentações podem deslocar os itens entre consultas de páginas diferentes.

### Idempotência

Depósitos, saques e transferências aceitam o cabeçalho opcional `Idempotency-Key`. Use uma chave nova por operação e repita a mesma chave apenas para tentar novamente a mesma requisição.

- A chave pertence ao cliente autenticado e aceita de 1 a 128 letras ASCII, dígitos ou os caracteres `.`, `_`, `:` e `-`.
- Repetir operação, contas e valor retorna `200` com a resposta original, sem nova movimentação. Valores como `25`, `25.0` e `25.00` são equivalentes.
- Reutilizar a chave com outros dados retorna `409`. Sem o cabeçalho, cada requisição bem-sucedida executa uma operação independente.
- Autenticação e propriedade são verificadas antes de recuperar a resposta. Operações que falham não têm resultado armazenado.
- Registros persistem após reinicializações e não expiram automaticamente. A resposta recuperada pode mostrar saldo ou estado antigos; consulte a conta para obter o estado atual.

### Estorno administrativo

O administrador envia `POST /admin/transactions/{id}/reversal` com um motivo não vazio de até 255 caracteres:

```json
{"reason": "Pagamento duplicado confirmado pelo suporte"}
```

O estorno é integral: cria uma movimentação inversa, preserva a original e registra administrador e motivo. As contas precisam estar ativas, com saldo suficiente para o débito e espaço para o crédito. Sucesso retorna `201`.

Cada transação permite apenas um estorno. Repetir um estorno ou tentar estornar outro estorno retorna `409`; este endpoint não usa `Idempotency-Key`. O extrato expõe os vínculos `originalTransactionId` e `reversalTransactionId`, sem divulgar o motivo interno ou o administrador. Repetir a chave idempotente da operação original não movimenta dinheiro novamente, mesmo após estorno.

### Respostas de erro

| Código | Significado |
|---|---|
| `400` | Entrada, JSON ou parâmetro inválido |
| `401` | Autenticação ausente ou inválida |
| `403` | Acesso não permitido |
| `404` | Recurso não encontrado |
| `409` | Conflito de cadastro, saldo, estado ou operação |

Os erros tratados pela API seguem o formato `timestamp`, `status`, `error`, `message` e `path`.

## Testes e integração contínua

Use um PostgreSQL separado para testes. Crie o banco uma vez:

```sql
CREATE DATABASE banking_api_test;
```

Por padrão, o perfil `test` usa `localhost:5432/banking_api_test`, usuário `postgres` e senha `admin`. Para substituir esses valores, configure `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` e `TEST_DATABASE_PASSWORD`. O perfil fornece seu próprio segredo JWT.

```powershell
.\mvnw.cmd clean verify
```

No Linux/macOS, use `./mvnw clean verify`. As suítes usam schemas de teste e limpam seus dados; não aponte essas variáveis para um banco de produção nem execute as suítes de banco em paralelo no mesmo ambiente.

A última validação da mudança de limites de entrada passou com **274 testes**, incluindo testes unitários, integração com PostgreSQL e HTTP real. A cobertura inclui autorização, concorrência, rollback, idempotência e estornos. Isso não substitui teste de carga ou avaliação de segurança em produção.

O GitHub Actions executa dois checks: `build` e `Docker Compose validation`. O segundo constrói a imagem, aguarda a saúde dos serviços e executa o teste HTTP do Compose. A `develop` exige PR, ambos os checks aprovados, branch atualizada e conversas resolvidas; force push e exclusão estão bloqueados, inclusive para administradores.

## Documentação complementar

- [Docker: configuração, persistência e diagnóstico](docs/docker.md)
- [CI: execução dos jobs e logs de falha](docs/ci.md)
- [Testes HTTP: execução e cobertura](docs/http-validation.md)

Os guias complementares estão em inglês e incluem registros de validações anteriores.

## Autor

Desenvolvido por **Ítalo André**. Projeto de portfólio em desenvolvimento backend com Java, Spring Boot e PostgreSQL.
