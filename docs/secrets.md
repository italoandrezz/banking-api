# Secrets por arquivo — Fase 4.4

O Compose recebe somente caminhos e opções não secretas. Valores são montados
individualmente, somente para leitura, em `/run/secrets`. Não são copiados à imagem,
passados em argumentos nem exportados ao ambiente da API ou do migrador.

| Arquivo no host | Consumidores | Destino |
|---|---|---|
| `admin_password` | PostgreSQL | `/run/secrets/admin_password` |
| `app_password` | PostgreSQL e API | `/run/secrets/app_password` no banco; `spring.datasource.password` na API |
| `migrator_password` | PostgreSQL e migrate | `/run/secrets/migrator_password` |
| `jwt_secret` | API | `/run/secrets/jwt.secret` |

Nginx não recebe secrets. Spring importa `configtree:/run/secrets/`; o validador
confere os dois arquivos antes da criação dos beans e rejeita overrides ou
variáveis legadas conflitantes. O migrador lê `FLYWAY_PASSWORD_FILE` sem Spring.
Leitura exige arquivo regular, legível, UTF-8, 1 a 4096 bytes, sem BOM, quebras de
linha ou caracteres de controle. Não há trim silencioso: preserve o valor exato.
JWT mantém a validação de tamanho/expiração existente; gere pelo menos 32 bytes
aleatórios representados em hexadecimal ou Base64, sem newline.

## Preparação e permissões

Crie um diretório exclusivo fora do repositório e informe seu caminho absoluto em
`SECRETS_DIR` no `.env`. `.secrets/` local também é ignorado, mas não é o local
recomendado. Nunca sobrescreva um `.env` ou diretório existente. Os quatro arquivos
devem ter valores distintos, obtidos de um gerenciador seguro, sem histórico de
terminal, stdout ou argumentos contendo valores.

No Linux, use diretório pai `0700` do operador. Os arquivos precisam ser legíveis
pelos UIDs não-root dos containers (API/migrate usam 10001). Os testes usam arquivos
`0444` dentro do pai `0700`: o host bloqueia travessia por outros usuários e cada
container recebe somente os arquivos autorizados. Em produção, prefira ownership
e ACLs específicas para o operador e UIDs envolvidos quando disponíveis.

No Windows 11, use Docker Desktop com containers Linux. Restrinja a ACL do diretório
ao operador e SYSTEM e remova herança ampla. Os runners aplicam isso com `icacls`
antes de gravar os arquivos. Permissões POSIX declaradas no Compose não substituem
ACLs do host: `uid/gid/mode` para secrets baseados em arquivo não são aplicados pelo
Compose como em um secret gerenciado. Confira acesso real com o usuário não-root.

```powershell
docker compose config --quiet
docker compose build api nginx
docker compose up -d --no-build --wait --wait-timeout 180
```

## Adoção em banco existente

Faça backup e ensaie em cópia isolada. A primeira adoção muda o transporte dos
segredos, não seus valores: transfira com segurança as credenciais atuais para os
arquivos e mantenha todos os nomes de papéis, banco e volume. Não regenere senhas
nem copie exemplos sobre configurações reais. Pare tráfego, execute o deploy
descrito em [migrations](migrations.md) e valide login, prontidão e permissões.
Um volume existente não é reprovisionado; mudar um arquivo não altera a role.
Remova valores legados do `.env` somente após conferir a transferência, tratando
cópias antigas conforme a política de retenção. Não remova volumes.

## Rotação e rollback

O responsável operacional deve manter inventário de donos, data de criação,
última rotação, próxima revisão e incidentes de cada secret no sistema de gestão
da organização, sem incluir os valores em tickets ou relatórios.

- **JWT:** suspenda tráfego e retire todas as instâncias antigas; substitua o
  arquivo e recrie todas as APIs. Teste token antigo (401), login novo e token novo.
  Não há recarga automática nem sobreposição de chaves. Alteração por substituição
  atômica do arquivo pode conservar o inode antigo em um bind existente: recrie os
  containers. Nunca reative uma chave comprometida para rollback.
- **Senha DML:** em janela de manutenção, altere a role por canal administrativo
  sem eco/log de SQL sensível, atualize o arquivo e recrie todas as APIs para
  renovar HikariCP. Valide nova conexão e rejeição da senha anterior.
- **Migrador:** sem migrations em andamento, altere a role e o arquivo; execute
  um migrador novo, confirme saída zero e preservação do histórico.
- **Administrador:** use acesso administrativo controlado, altere role e arquivo;
  valide nova autenticação antes de encerrar a sessão de recuperação. A variável
  `POSTGRES_PASSWORD_FILE` não rotaciona uma role de volume já inicializado.

Antes da rotação, preserve backup e meios de recuperação autorizados. Falhas na
adoção, sem troca de valores, permitem voltar à configuração anterior conservando
o banco; isso reintroduz a exposição por ambiente e precisa de decisão operacional.
Após rotação, prefira corrigir consumidores para o segredo novo. Um segredo
comprometido nunca é opção de rollback. Restauração ocorre em banco novo, conforme
[recuperação](recovery.md), sem sobrescrever o original.

## Desenvolvimento, testes e CI

Fora do Compose, o modo legado explícito por variáveis continua disponível para
fixtures e desenvolvimento. Para modo arquivo na API, configure
`SPRING_CONFIG_IMPORT=configtree:<diretório>/` e `BANKING_SECRETS_DIRECTORY` para
o mesmo diretório contendo `spring.datasource.password` e `jwt.secret`, sem
variáveis de senha/JWT. O migrador aceita valor legado OU `_FILE`, nunca ambos.
Não ative o profile `test` em produção.

Postman/Nginx geram segredos temporários aleatórios em diretórios privados, validam
as montagens autorizadas e eliminam arquivos junto com seus recursos Docker.
O CI grava em `RUNNER_TEMP` e passa apenas `SECRETS_DIR` por `GITHUB_ENV`; falha na
limpeza reprova o job. Somente relatórios sanitizados são artifacts.
O Postman também rotaciona JWT e senha DML após os testes financeiros/recuperação,
exclusivamente no banco tmpfs, e confirma rejeição dos valores antigos.

## Limites

Compose local usa arquivos montados, não um cofre criptografado. Administradores
do host/Docker podem lê-los; os processos consumidores precisam do valor em memória.
O entrypoint oficial PostgreSQL pode exportar temporariamente a senha administrativa
ao processar `_FILE`; o provisionamento usa variáveis transitórias para psql. A
garantia de ausência de valores em `Config.Env` não significa ausência em toda
memória ou ambiente interno de bootstrap. Ferramentas de teste também usam
`PGPASSWORD` transitoriamente para provar autenticação por TCP.
Encerramento forçado/perda do host pode impedir cleanup: verifique diretórios
privados e recursos da execução antes de descartá-los. Nunca publique inspect,
ambientes nativos, backups, logs SQL ou arquivos secretos como artifacts.
