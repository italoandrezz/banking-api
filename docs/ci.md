# Integração contínua

O workflow `.github/workflows/ci.yml` executa em PRs para `develop` e `main`, além de pushes na `develop`. Mantém `contents: read` e os dois checks obrigatórios.

| Check | Validação |
|---|---|
| `build` | Java 17, PostgreSQL temporário e `./mvnw clean verify`, incluindo o migrador independente |
| `Docker Compose validation` | Testes Node.js, imagem Docker compartilhada API/migrador, smoke HTTP e collection Postman |

## Fluxo Docker e Postman

1. Instalar Node.js 24.19.0 e executar `node --test scripts/postman/*.test.mjs`: testes de infraestrutura Postman, relatórios, recuperação e segurança.
2. Instalar Postman CLI 1.74.0 para Linux x64 com `scripts/ci/install-postman.sh`. O download oficial versionado precisa corresponder ao SHA-256 fixado no script. A versão do executável também é conferida.
3. Gerar arquivos privados temporários com senhas distintas e JWT em `RUNNER_TEMP`. Somente o caminho `SECRETS_DIR` é transmitido por `GITHUB_ENV`; nenhum valor é impresso. A limpeza remove os arquivos e falha se não conseguir concluí-la.
4. Validar o Compose principal e construir a imagem uma única vez.
5. Subir PostgreSQL, executar `migrate` até saída zero e iniciar API/Nginx; verificar imagem compartilhada e ausência de credenciais DDL no runtime com `scripts/ci/verify-migrations.mjs`; executar o smoke de 15 requisições.
6. Remover containers, rede e volume desse projeto e verificar a ausência de recursos. Falhas de comando ou recursos remanescentes reprovam o job.
7. Executar `node scripts/postman/run.mjs --image "$API_IMAGE_ID" --recovery`. O runner aceita somente um ID local imutável `sha256:...`, verifica sua existência e reutiliza a imagem sem build ou download da API. Não remove a imagem emprestada.
8. Provar que uma migration com falha impede a API de iniciar; executar novamente com configuração válida; verificar isolamento de credenciais e privilégios reais por conexão SQL autenticada; executar 185 requisições e 1047 assertions com usuário restrito em outro projeto Compose aleatório, com PostgreSQL em tmpfs, sem dados persistentes ou banco externo. Somente provisionamento e arquivos secretos da própria execução são binds permitidos, sempre para leitura. Ao final, validar rotação JWT e DML no banco descartável.
9. Limpar os recursos Postman e o diretório privado, inclusive em falhas tratáveis.
10. Gerar e publicar somente os relatórios explicitamente permitidos.

## Relatórios e falhas

O artifact `api-validation-<run_id>-<run_attempt>` tem retenção de sete dias:

- `target/ci/junit.xml` e `summary.md`: resultado das etapas, inclusive falha, cancelamento, ausência ou execução ignorada, sem mensagens nativas.
- `target/postman/latest/junit.xml` e `summary.md`: resultados HTTP sanitizados, quando o runner chegou a gerar os relatórios.

O JUnit do pipeline representa etapas; o JUnit Postman representa requisições e erros globais. Não some ambos como quantidade de testes HTTP. Instalação que falhe antes do Postman gera erro no relatório do pipeline. Assertions, execução parcial, infraestrutura, interrupção e limpeza incompleta não produzem resultado Postman verde.

Logs de build, inicialização e limpeza ficam apenas no diretório temporário do runner. Não são enviados logs dos serviços, relatórios nativos Postman, ambientes privados, headers, corpos HTTP nem XML bruto do Surefire. Os resultados Java continuam disponíveis no job `build`. O upload ocorre também em falhas, com uma lista explícita de oito arquivos JUnit/Markdown.

Em cancelamento forçado, perda do runner ou término abrupto não há garantia de execução do `finally` nem de upload. No GitHub, os recursos pertencem à VM efêmera; check cancelado não equivale a aprovação.

## Atualização do CLI

A origem é a [distribuição oficial do Postman CLI](https://learning.postman.com/docs/postman-cli/postman-cli-installation/). Para atualizar, baixar a versão explícita de `dl-cli.pstmn.io`, revisar a origem e o novo SHA-256, conferir `--version` e repetir os testes. O hash foi calculado do arquivo recebido via HTTPS; não é uma assinatura independente do fornecedor. Arquivo ou versão divergente reprova a instalação. Não usamos `latest`, login Postman nem execução do instalador remoto mutável.

## Validação e publicação

Testes locais e execução em container não substituem um run real do GitHub Actions. Em cada PR, confira ambos os checks, os testes de privilégios/upgrade e os relatórios publicados. A Fase 1 foi validada no PR #39; a execução de cada alteração seguinte deve ser confirmada no respectivo PR. Nenhuma regra de proteção é modificada pelo workflow.

## Segurança e recuperação (Fase 3)

O `build` exporta a árvore Maven efetivamente resolvida após `clean verify` e gera
um inventário CycloneDX sanitizado com Node.js 24.19.0. O artifact
`maven-dependencies-<run_id>-<run_attempt>` contém somente `maven.cdx.json`, com
retenção de um dia. O job Compose depende do build e baixa esse inventário da mesma
execução; falha ou ausência do inventário impede a análise de segurança.

Antes de subir os serviços, `scripts/security/scan.mjs` executa Trivy 0.75.0 fixado
por digest, sem socket Docker no scanner. Analisa a imagem final e as 124 coordenadas
Maven (inclusive testes), verificando cobertura completa. Reutiliza a mesma imagem
para smoke e Postman. A política bloqueia erros do scanner e achados relevantes;
exceções Spring MVC têm versão, justificativa e expiração explícitas. Consulte
[dependências e segurança](dependency-security.md).

O CI também constrói a imagem Nginx e passa seu ID local imutável ao mesmo runner,
com `node scripts/security/scan.mjs "$NGINX_IMAGE_ID" --nginx`. Esse modo exige
inventário de pacotes do sistema contendo Nginx, sem exigir JARs inexistentes no
proxy. Severidades, correções disponíveis e bloqueios permanecem os mesmos;
a cobertura obrigatória da imagem Java e do inventário Maven não muda. Falha em
qualquer scan reprova a etapa `security` e o check `Docker Compose validation`.

O artifact de validação também contém `target/security/junit.xml` e `summary.md`,
além de `target/nginx-security/junit.xml` e `summary.md` para o proxy.
Não inclui JSON bruto do Trivy, imagem exportada, dump, ambiente privado ou logs
nativos. Os relatórios de pipeline incluem a etapa `security`; erro, cancelamento,
execução omitida e limpeza não confirmada não podem produzir resultado verde.

Postman usa `--recovery`: após a collection, testa reinício com redescoberta da porta,
replay, indisponibilidade temporária, backup/restauração, permissões e HTTP contra
o banco restaurado. Com a Etapa 4.1, também exige liveness UP e readiness 503 durante
a indisponibilidade real do banco e readiness UP após recuperação. As sete verificações geram casos JUnit adicionais (192 casos
na execução bem-sucedida). As 185 requisições e 1047 assertions da collection
continuam contadas separadamente. Veja o [procedimento operacional](recovery.md).

A validação local de 16/10/2026 aprovou Java, Node.js, scanner, smoke, Postman e
recuperação. A comprovação Ubuntu depende da execução real do PR desta fase;
nenhum resultado local substitui checks, logs, artifacts e regras de proteção.
