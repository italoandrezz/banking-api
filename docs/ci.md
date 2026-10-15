# Integração contínua

O workflow `.github/workflows/ci.yml` executa em PRs para `develop` e `main`, além de pushes na `develop`. Mantém `contents: read` e os dois checks obrigatórios.

| Check | Validação |
|---|---|
| `build` | Java 17, PostgreSQL temporário e `./mvnw clean verify`: 299 testes Java |
| `Docker Compose validation` | 24 testes Node.js, imagem Docker, smoke HTTP do Compose e collection Postman |

## Fluxo Docker e Postman

1. Instalar Node.js 24.19.0 e executar `node --test scripts/postman/*.test.mjs`: os 22 testes existentes e dois novos para os relatórios do pipeline.
2. Instalar Postman CLI 1.74.0 para Linux x64 com `scripts/ci/install-postman.sh`. O download oficial versionado precisa corresponder ao SHA-256 fixado no script. A versão do executável também é conferida.
3. Gerar credenciais temporárias, mascaradas nos logs do Actions.
4. Validar o Compose principal e construir a imagem uma única vez.
5. Subir API e PostgreSQL, aguardar healthchecks e executar o smoke de 15 requisições.
6. Remover containers, rede e volume desse projeto e verificar a ausência de recursos. Falhas de comando ou recursos remanescentes reprovam o job.
7. Executar `node scripts/postman/run.mjs --image "$API_IMAGE_ID"`. O runner aceita somente um ID local imutável `sha256:...`, verifica sua existência e reutiliza a imagem sem build ou download da API. Não remove a imagem emprestada.
8. Executar 185 requisições e 1047 assertions em outro projeto Compose aleatório, com PostgreSQL em tmpfs, sem volumes persistentes ou banco externo.
9. Limpar os recursos Postman e o diretório privado, inclusive em falhas tratáveis.
10. Gerar e publicar somente os relatórios explicitamente permitidos.

## Relatórios e falhas

O artifact `api-validation-<run_id>-<run_attempt>` tem retenção de sete dias:

- `target/ci/junit.xml` e `summary.md`: resultado das etapas, inclusive falha, cancelamento, ausência ou execução ignorada, sem mensagens nativas.
- `target/postman/latest/junit.xml` e `summary.md`: resultados HTTP sanitizados, quando o runner chegou a gerar os relatórios.

O JUnit do pipeline representa etapas; o JUnit Postman representa requisições e erros globais. Não some ambos como quantidade de testes HTTP. Instalação que falhe antes do Postman gera erro no relatório do pipeline. Assertions, execução parcial, infraestrutura, interrupção e limpeza incompleta não produzem resultado Postman verde.

Logs de build, inicialização e limpeza ficam apenas no diretório temporário do runner. Não são enviados logs dos serviços, relatórios nativos Postman, ambientes privados, headers, corpos HTTP nem XML bruto do Surefire. Os resultados Java continuam disponíveis no job `build`. O upload ocorre também em falhas, com uma lista explícita de quatro arquivos.

Em cancelamento forçado, perda do runner ou término abrupto não há garantia de execução do `finally` nem de upload. No GitHub, os recursos pertencem à VM efêmera; check cancelado não equivale a aprovação.

## Atualização do CLI

A origem é a [distribuição oficial do Postman CLI](https://learning.postman.com/docs/postman-cli/postman-cli-installation/). Para atualizar, baixar a versão explícita de `dl-cli.pstmn.io`, revisar a origem e o novo SHA-256, conferir `--version` e repetir os testes. O hash foi calculado do arquivo recebido via HTTPS; não é uma assinatura independente do fornecedor. Arquivo ou versão divergente reprova a instalação. Não usamos `latest`, login Postman nem execução do instalador remoto mutável.

## Validação e publicação

Testes locais e execução do instalador Linux em container não substituem um run real do GitHub Actions. A validação Ubuntu no GitHub permanece pendente até a publicação autorizada de uma branch e abertura do PR. Nesse PR, conferir ambos os checks e os relatórios. Nenhuma regra de proteção é modificada pelo workflow.
