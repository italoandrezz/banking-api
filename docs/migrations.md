# Migrations fora do runtime — Fase 4.3

O serviço temporário `migrate` usa a mesma imagem da API e inicia somente
`com.italo.bankingapi.migration.MigrationRunner`, via `PropertiesLauncher`.
Não cria contexto Spring, servidor HTTP, JPA nem configuração JWT. O build fixa
o main da API para que os dois pontos de entrada não tornem o empacotamento ambíguo.
Não há dependência nova nem alteração nas migrations V1–V5 ou no callback existente.

## Identidades e inicialização

- `db`: provisiona três papéis somente quando o volume está vazio. Conserva as
  credenciais de bootstrap; não altera senhas de um volume existente.
- `migrate`: recebe somente `MIGRATION_DATABASE_URL`, `FLYWAY_USERNAME` e
  `FLYWAY_PASSWORD_FILE`, com montagem exclusiva da senha. Conecta ao schema `public` como proprietário não superusuário.
  Valida checksums, aplica migrations e executa o callback de permissões.
- `api`: recebe somente credenciais DML e JWT; `SPRING_FLYWAY_ENABLED=false`,
  Hibernate `ddl-auto=validate`. Não recebe variáveis do migrador nem administrador.
- `nginx`: inicia após a API ficar saudável. O migrador não publica portas e,
  no Compose principal, participa somente da rede interna do banco.

`service_completed_successfully` bloqueia a criação/inicialização da API quando
o migrador falha. A imagem e o healthcheck HTTP são compartilhados, mas o
healthcheck do migrador é desativado e ele não reinicia automaticamente.
O runner recusa papéis com privilégios administrativos. Flyway clean, baseline automático, out-of-order e criação automática de schema
ficam desativados. O runner não aceita argumentos como `repair` ou `clean`.

## Operação em banco novo

Configure `.env` com `SECRETS_DIR` e prepare os [arquivos privados](secrets.md), então execute:

```powershell
docker compose config --quiet
docker compose build api nginx
docker compose up -d --no-build --wait --wait-timeout 180
docker compose ps --all
docker compose logs migrate
```

O único log do runner é um evento JSON fixo `migration_completed` com a quantidade
aplicada ou `migration_failed` com a fase. Falhas retornam código 1; não imprimem
SQL, URL, credenciais ou stack trace. Diagnóstico exige inspeção controlada do
histórico e da conectividade por operador autorizado; não habilite logs verbosos
da aplicação para obter essas informações.

## Deploy em volume existente

Não basta reiniciar a API: ela não executa migrations. Não reutilize como evidência
um container `migrate` concluído por uma release anterior. `depends_on` também não
interrompe APIs que já estavam atendendo. Use janela de manutenção:

1. Confirme o projeto/volume, faça backup consistente e ensaie a restauração em
   cópia isolada. Confira papéis, proprietários, `banking.application_role`,
   versão/checksums do histórico e compatibilidade com a release. Se faltarem
   papéis ou ownership, pare e siga o procedimento PostgreSQL, sem reprovisionar.
2. Construa/identifique a nova imagem; mantenha a imagem anterior por digest.
3. Pare tráfego e instâncias: `docker compose stop nginx api`.
4. Remova **somente o container encerrado** do migrador:
   `docker compose rm -f migrate`. Isso não remove o banco nem seu volume.
5. Execute `docker compose up -d --no-build --wait --wait-timeout 180`.
   Verifique código zero do migrador, healthchecks e smoke controlado antes
   de reabrir tráfego. Pare em qualquer falha; não execute repair/baseline.

O callback existente reaplica GRANT/REVOKE. Por isso o deploy não promete ausência
de interrupção. Um advisory lock de sessão por banco (chave `4774188638755047250`)
envolve todo o runner, inclusive o callback: somente o lock do histórico Flyway
não impedia `tuple concurrently updated` durante GRANT/REVOKE concorrentes.
A espera é limitada a 120 segundos, consultas a 5 segundos; falhas impedem startup.
A conexão adicional é encerrada ao concluir ou falhar, liberando o lock.
Ferramentas externas que não utilizam esse runner não participam desse protocolo;
não execute outro cliente Flyway simultaneamente. Isso não substitui coordenação
de deploy nem compatibilidade de schema entre releases. Consulte a documentação
de [advisory locks PostgreSQL](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS).
Não use `down --volumes`, `clean`, nem modifique migrations aplicadas.

Containers encerrados continuam visíveis ao administrador Docker, inclusive suas
montagens de secrets. Após coletar evidência de saída zero e iniciar a API, pode-se remover
somente `migrate` com `docker compose rm -f migrate`. Um próximo `up` o executará
novamente; restart da API não o executa. Secrets por arquivo e limites de acesso estão no [guia da Fase 4.4](secrets.md).

## Sem Compose

Empacote o jar e execute em um processo exclusivo, com apenas as três variáveis
do migrador (credenciais injetadas pelo operador, nunca na linha de comando):

```powershell
java '-Dloader.main=com.italo.bankingapi.migration.MigrationRunner' -cp target/banking-api-service-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
if ($LASTEXITCODE -ne 0) { throw 'Migration falhou; não iniciar a API' }
```

Inicie a API em outro processo/ambiente contendo somente credenciais runtime e
JWT. Não herde variáveis DDL; não ative o profile `test`. O profile de testes
reativa Flyway exclusivamente para fixtures legadas em schema descartável.

## Recuperação e rollback

Restaure primeiro em banco novo e isolado usando a mesma versão da aplicação e
valide dados, histórico, ownership e permissões. O runner de recuperação inicia
a API restaurada com `--no-deps`, sem migrador, e verifica replay idempotente.
Upgrade da cópia restaurada é uma etapa posterior explícita.

Esta fase não altera schema: se for preciso reverter, use a imagem e configuração
anteriores revisadas, mantendo o volume e os checksums. Isso reintroduz o risco
de credenciais DDL no runtime antigo; requer decisão operacional explícita.
Para futuras migrations incompatíveis, voltar somente a imagem não é rollback
seguro. Use correção progressiva revisada ou restauração em banco novo e troca
controlada de destino, conforme [recuperação](recovery.md). Nunca restaure sobre
o banco original para contornar uma falha de deploy.

## Evidências automatizadas

Java testa configuração, upgrade com dados, permissões DML, checksum inválido,
conectividade/autenticação inválidas e migradores concorrentes. Postman provoca
uma migration com senha inválida em seu PostgreSQL tmpfs e confirma que a API não
inicia; depois valida a execução real do jar, imagem compartilhada e isolamento
das credenciais, antes das 185 requisições. O Nginx valida duas réplicas runtime.
CI preserva os checks `build` e `Docker Compose validation`; falhas e limpeza
incompleta reprovam o pipeline. Relatórios sanitizados ficam em `target/`.
