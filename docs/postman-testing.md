# Testes de endpoints com Postman

A collection executa 185 requisições e 1047 assertions contra os 18 endpoints dos Controllers, além de health e OpenAPI. Usa HTTP real, API Java e PostgreSQL em containers descartáveis. Não utiliza mocks nem o banco de desenvolvimento.

## Requisitos

- Docker em execução com containers Linux e Compose v2 ou posterior.
- Node.js 22 ou posterior; validado com 24.19.0, sem pacotes npm adicionais.
- Postman CLI com `--no-report-events` e reporter JSON compatível; validado com 1.74.0.
- Internet para baixar imagens e dependências na primeira construção.
- VS Code para as tasks; extensão Postman somente para inspeção manual.

Instalação oficial: https://learning.postman.com/docs/postman-cli/postman-cli-installation/

No Windows, o runner reconhece `%LOCALAPPDATA%/Programs/PostmanCLI/postman.exe` ou o executável no PATH. Reinicie o terminal após alterações no PATH. Java e Maven locais não são necessários para esta suíte: a imagem existente realiza o build.

## Executar pelo VS Code

1. Abra a pasta que contém `pom.xml` e `.vscode/tasks.json`.
2. Execute **Terminal > Run Task > Postman: testar API isolada**.
3. Aguarde o resultado e a confirmação da limpeza.
4. Execute **Postman: abrir resultados** ou abra `target/postman/latest/summary.md`.

A tarefa de resultados usa `${execPath}`, o executável do VS Code em uso. Isso evita depender de `%LOCALAPPDATA%` ou do comando `code` no PATH e permite instalações por usuário, por máquina e portáteis no Windows. Referência: [variáveis de tarefas do VS Code](https://code.visualstudio.com/docs/reference/variables-reference).

Alternativas no terminal, a partir da mesma pasta:

```powershell
.\scripts\Test-Postman.ps1
```

```sh
node scripts/postman/run.mjs
```

Código de saída zero significa suíte aprovada e limpeza confirmada. Qualquer falha retorna código diferente de zero. Não configure `.env`, porta fixa ou credenciais para executar estes testes.

## Isolamento e limpeza

`compose.postman.yaml` é independente do Compose de desenvolvimento. Cada execução recebe projeto aleatório `banking-postman-...`, rede própria, porta aleatória em `127.0.0.1` e PostgreSQL em `tmpfs`. Não há volumes de dados persistentes nem porta publicada para o banco. O único bind é o código de provisionamento PostgreSQL, somente para leitura. O runner verifica essas propriedades antes de preparar os dados e não aceita uma URL externa como argumento.

O arquivo `.env` de desenvolvimento não é carregado. Senhas, segredo JWT e usuários de teste são gerados por execução. Um cliente sintético é promovido a ADMIN diretamente no banco isolado para testar estornos; isso não altera a aplicação nem cria um administrador no banco de desenvolvimento. Um JWT expirado, com assinatura válida, é gerado somente para o cenário de rejeição.

Credenciais temporárias e o relatório bruto ficam em diretório privado temporário, removido no `finally`. Credenciais e tokens capturados pela collection usam variáveis locais da execução. O runner usa collection local sem vínculo com workspace remoto e `--no-report-events`; não precisa de login no Postman CLI.

Ao terminar, inclusive após erros, o runner remove os containers, a rede e a imagem local daquele projeto, verifica os recursos restantes e apaga os arquivos privados. Nenhum volume existente é apagado. Imagens base e cache compartilhado de build podem permanecer. Ctrl+C é tratado; desligamento da máquina, encerramento forçado do processo ou indisponibilidade do Docker podem impedir a limpeza. Nesse caso, confira o projeto aleatório impresso e remova somente seus recursos; não use `docker system prune` ou comandos sobre o Compose de desenvolvimento.

Para testar o caminho de falha e limpeza:

```sh
node scripts/postman/run.mjs --simulate-failure
```

Essa execução falha intencionalmente após subir os serviços. O esperado é código 1, etapa `simulated-failure` e limpeza confirmada. Ela substitui o relatório `latest`; execute a suíte normal novamente para atualizar o resultado.

## Cobertura

| Método | Endpoint | Cenários principais |
|---|---|---|
| POST | `/auth/login` | Credenciais válidas e inválidas, validação do corpo |
| GET | `/auth/me` | Identidade e papel, JWT ausente, inválido, expirado e adulterado |
| POST | `/customers` | Cadastro, duplicidade, campos inválidos, tentativa de definir papel |
| GET | `/customers` | Listagem proibida para cliente autenticado |
| GET | `/customers/{id}` | Próprio cliente, titularidade, inexistente e ID inválido |
| PUT | `/customers/{id}` | Perfil, titularidade, validações e duplicidade |
| DELETE | `/customers/{id}` | Exclusão, vínculos financeiros e titularidade |
| POST | `/accounts` | Conta própria com saldo zero, titularidade e cliente inexistente |
| GET | `/accounts` | Apenas contas do titular |
| GET | `/accounts/{id}` | Saldo, estado, titularidade e inexistente |
| PATCH | `/accounts/{id}/block` | Bloqueio, estado repetido e titularidade |
| PATCH | `/accounts/{id}/unblock` | Desbloqueio, estados inválidos e titularidade |
| PATCH | `/accounts/{id}/close` | Encerramento, saldo não zero, estado e titularidade |
| POST | `/accounts/{id}/deposit` | Valores, saldo máximo, estado, titularidade e idempotência |
| POST | `/accounts/{id}/withdraw` | Valores, saldo insuficiente, estado, titularidade e idempotência |
| POST | `/accounts/transfer` | Saldos, destino, titularidade, estado, limite e idempotência |
| GET | `/accounts/{id}/transactions` | Histórico, paginação, filtros, validações e titularidade |
| POST | `/admin/transactions/{id}/reversal` | Papel ADMIN, três tipos de estorno, motivo, saldo e repetição |

Todos os 16 endpoints protegidos são chamados também sem JWT e com JWT inválido. Os testes verificam status HTTP, respostas JSON, formato dos erros, ausência de senha/hash, saldos e histórico. A idempotência inclui repetição, conflito de payload, escopo por cliente e nova tentativa após falha sem persistir resultado inválido. Créditos acima do limite verificam também a preservação do saldo da origem.

A inspeção de campos sensíveis percorre objetos e arrays das respostas de sucesso e erro. Compara nomes completos normalizados, como `password`, `passwordHash`, `hashed_password`, `secret`, `privateKey` e `accessToken`; não procura palavras no texto de mensagens. Campos legítimos como `passwordChangedAt`, `hasPassword` e `tokenType` continuam permitidos. Somente o `token` na raiz do login bem-sucedido é permitido pelo contrato. Respostas sem corpo e o documento OpenAPI, que descreve campos de entrada, não passam por essa inspeção. A lista explícita de nomes fica em `scripts/postman/sensitive-fields.mjs` e deve acompanhar mudanças de contratos.

As pastas devem executar em ordem, com uma iteração: os fluxos compartilham dados em memória. A suíte é sequencial e não substitui testes de concorrência, carga, segurança ou os testes Java existentes.

## Relatórios

Cada execução grava `target/postman/<projeto>/`; `target/postman/latest/` contém a mais recente:

- `summary.md`: contagem, cobertura por endpoint e falhas.
- `report.html`: resultado de cada requisição e assertion, para abrir no navegador.
- `summary.json`: resultados estruturados sanitizados.
- `junit.xml`: um caso por requisição e casos adicionais com `<error>` para falhas globais, execução parcial, infraestrutura, interrupção ou limpeza não confirmada. Falhas de assertions usam `<failure>`; os totais correspondem aos elementos do XML.

Somente nomes estáticos conhecidos, rotas, códigos HTTP e resultados são preservados. Corpos, headers, valores de erros nativos, tokens, credenciais e identificadores dos clientes não são exportados. Relatórios ficam ignorados pelo Git. O sanitizador reprova execução incompleta, assertions omitidas ou ignoradas e falhas de scripts.

## Visualizar na extensão Postman

Os relatórios locais do CLI não aparecem automaticamente no painel de execuções da extensão. Para o resultado completo desta suíte, use os arquivos acima dentro do VS Code ou no navegador.

Para inspecionar requisições e seus testes na extensão:

1. Importe `postman/banking-api.postman_collection.json` e o template vazio `postman/local.postman_environment.json`.
2. Execute a task **Postman: testar e inspecionar por 10 minutos**.
3. Após a suíte, copie somente `baseUrl` e `isolationId` exibidos no terminal para o ambiente importado e selecione-o.
4. Envie as requisições de health e OpenAPI da primeira pasta e veja as assertions na área **Test Results** da resposta. Também é possível executar essa pasta no Collection Runner.
5. Ctrl+C encerra a janela de inspeção e registra a interrupção como falha da execução; após dez minutos a limpeza é automática. Durante a janela, o JUnit indica limpeza ainda não confirmada; o resultado final é gravado ao encerrar.

A execução integral, incluindo o administrador e o JWT expirado, é preparada pelo runner e deve ser feita pela task. O template importável não contém essas credenciais. As variáveis locais de um fluxo não persistem entre envios manuais independentes. Não importe o ambiente privado temporário nem salve tokens em exemplos compartilhados.

Consulte a [documentação oficial dos testes na extensão](https://learning.postman.com/docs/reference/vs-code-extension/tests-and-scripts/overview/). A interação gráfica da extensão não foi automatizada nesta validação.

## Manutenção e validação realizada

Edite `scripts/postman/build-collection.mjs`, regenere o JSON e valide a infraestrutura:

```sh
node scripts/postman/build-collection.mjs
node --test scripts/postman/report.test.mjs
node scripts/postman/run.mjs
```

Resultado validado: 185 requisições, 1047 assertions, zero reprovações e limpeza confirmada. O teste de falha deliberada confirmou a limpeza e a representação da falha em JUnit e Markdown. Os 22 testes da infraestrutura verificam inspeção recursiva, exceção do token de login, ausência de falsos positivos, sanitização, execução incompleta, relatórios JUnit, scripts, isolamento, tarefas e sincronização da collection.

Na primeira execução, uma assertion esperava apenas `application/json` para o Actuator. A resposta válida usa subtipo JSON do Spring Boot; a assertion foi corrigida para aceitar `application/*+json`. A revisão posterior substituiu a regex de campos sensíveis pela inspeção recursiva e corrigiu a representação de falhas globais no JUnit. Nenhuma regra de negócio foi alterada. A validação Java com `mvnw.cmd -B -ntp verify` executou 299 testes com PostgreSQL descartável, sem falhas, erros ou testes ignorados.

## Integração no GitHub Actions

O check `Docker Compose validation` executa os 24 testes Node.js, mantém o smoke do Compose principal e executa a collection completa em um novo PostgreSQL temporário. O `build` executa 302 testes Java: os 299 anteriores e três testes de privilégios e upgrade PostgreSQL.

O runner pode reutilizar uma imagem já construída com:

```powershell
$imageId = docker image inspect NOME_DA_IMAGEM_LOCAL --format '{{.Id}}'
node scripts/postman/run.mjs --image $imageId
```

Somente IDs locais `sha256:...` são aceitos; tags, registros remotos e URLs de API não são aceitos. A imagem emprestada permanece disponível após o teste; a imagem construída pelo próprio runner é removida na limpeza. Sem `--image`, as tasks existentes do VS Code continuam construindo a imagem automaticamente. A normalização de finais de linha no gerador mantém a collection consistente entre Windows e Linux.

O CI instala Postman CLI 1.74.0 com versão e SHA-256 fixados. Publica somente JUnit e Markdown sanitizados, incluindo o resultado das etapas de infraestrutura. Ambientes privados, relatórios nativos e logs brutos não são artifacts. Consulte [CI](ci.md) para limites de cancelamento, política de atualização e lista dos relatórios.

A Fase 1 foi validada em Ubuntu no PR #39. Cada alteração posterior exige confirmação dos checks e artifacts no próprio PR; resultados locais não substituem essa confirmação.

O PostgreSQL descartável utiliza três identidades e senhas aleatórias distintas: `postman_admin` para provisionamento/fixture administrativa, `postman_migrator` para Flyway e `postman_test` para o datasource da API. Antes da collection, uma conexão TCP autenticada como aplicação verifica atributos administrativos, memberships, propriedade, permissões de schema/banco e acesso ao histórico Flyway. Falhas reprovam a execução. O único bind aceito é `/docker-entrypoint-initdb.d`, somente para leitura, no container do banco; os dados continuam em tmpfs. Veja [segurança PostgreSQL](postgresql-security.md).
