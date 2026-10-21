# Segurança operacional — Fase 4.2.2

Este documento registra procedimentos e pendências. Não executa rotação, migrations,
alteração de credenciais ou implantação. As Fases 4.3 e 4.4 dependem de autorização
própria; suas medidas ainda não estão implementadas.

## Rotação emergencial do JWT

A API usa uma única chave HMAC em `JWT_SECRET`, carregada ao iniciar o processo.
Não há chave anterior aceita em paralelo, lista de revogação ou logout que invalide
tokens no servidor. A duração máxima configurável é de 24 horas.

Em suspeita de exposição da chave, trate todos os tokens assinados com ela como
comprometidos, incluindo os que um atacante possa emitir. Procedimento:

1. Identifique o ambiente, o projeto Compose e todas as réplicas da API. Abra uma
   janela de manutenção e suspenda a entrada de requisições. Aguarde operações
   em andamento conforme o procedimento operacional e retire todas as instâncias
   antigas de serviço, inclusive acessos internos. A rotação não desfaz transações
   já confirmadas.
2. Gere uma chave nova e independente usando um gerador criptograficamente seguro:
   pelo menos 32 bytes aleatórios representados em Base64 é uma opção. A aplicação
   interpreta esse resultado como texto UTF-8, sem decodificar Base64. Não use
   senha humana, chave de testes ou credencial PostgreSQL.
3. Grave a nova chave pelo canal seguro do ambiente. No ambiente local atual,
   atualize somente `JWT_SECRET` no `.env` existente, com permissões restritas,
   sem imprimir o valor, colocá-lo em argumentos de comandos, histórico, tickets
   ou relatórios. Não copie `.env.example` sobre um `.env` preenchido.
4. Recrie todas as instâncias da API para carregar a nova variável; em processos
   Java fora do Compose, reinicie-os com o ambiente atualizado. `docker compose
   restart` reutiliza o ambiente do container e não aplica a troca no `.env`.
   Não faça rollout misto com chaves diferentes: instâncias antigas ainda
   aceitariam tokens comprometidos. Não remova volumes ou altere o banco.
5. Com o tráfego externo ainda suspenso, confira liveness/readiness em todas as
   réplicas. Verifique, sem registrar o token, que um token anterior recebe 401
   em uma rota autenticada. Efetue um novo login e confirme acesso com o token
   recém-emitido. Registre apenas status, horário e correlation ID; não registre
   headers Authorization, senhas ou corpos de login.
6. Retome o tráfego somente após confirmar que nenhuma instância antiga permanece
   acessível. Todos os usuários precisarão autenticar novamente. Remova cópias
   operacionais da chave antiga e trate sua eventual presença em backups e logs
   conforme a política de retenção e resposta ao incidente.

Trocar a chave invalida criptograficamente os tokens anteriores em todas as
instâncias atualizadas, mesmo antes de sua expiração. Alterar `JWT_EXPIRATION`,
trocar a senha de um usuário ou apagar o token no cliente não revoga um JWT já
emitido por si só. Não restaure uma chave comprometida para fazer rollback:
restaure a aplicação com a chave nova ou gere outra e repita a validação.
Investigue a origem da exposição antes de encerrar o incidente.

## Configuração sensível atual

- `.env.example` contém nomes e valores não secretos; os campos de credenciais
  permanecem vazios. `.env` é excluído pelas regras locais do Git e pelo contexto
  de build da API. Isso não remove segredos eventualmente publicados no passado
  nem protege cópias, backups ou permissões locais excessivas.
- Administrador, migrador e aplicação usam credenciais distintas. O `.env` não
  altera automaticamente senhas de roles em volumes existentes; siga o
  [procedimento PostgreSQL](postgresql-security.md).
- O processo Java ainda recebe as credenciais do Flyway e executa migrations na
  inicialização. Secrets em variáveis dos containers continuam visíveis a quem
  administra o Docker. Evite publicar saídas de `docker inspect` ou de
  `docker compose config` sem sanitização.
- A chave fixa e as credenciais de fixtures pertencem exclusivamente aos testes.
  Não as reutilize em produção. O profile `prod` desabilita documentação, mas não
  fornece um gerenciador de secrets nem separa o Flyway.
- Para ativar `prod`, o profile deve chegar ao processo Spring. Adicionar
  `SPRING_PROFILES_ACTIVE` somente ao `.env` não o transmite automaticamente pelo
  Compose atual, que não declara essa variável. Configuração de implantação
  explícita continua necessária; este documento não altera o Compose.

## CVEs Spring e prazo das exceções

As exceções para CVE-2026-47884 e CVE-2026-47890 aplicam-se somente a
`spring-webmvc:6.2.19`, sob as condições registradas no
[guia de dependências](dependency-security.md). A versão instalada continua
afetada; não há declaração de correção nem de falso positivo.

`scripts/security/policy.mjs` deixa de aceitar as exceções em
**15/11/2026 às 00:00 UTC** (14/11/2026 às 21:00 em Fortaleza). A decisão sobre
atualização e eventual suporte comercial deve ocorrer antes desse instante.
Não prorrogar automaticamente nem reduzir a severidade para aprovar o CI.
Novos usos de XSLT/views/SSE também exigem revisão antes do prazo.

## Fase 4.3 — Flyway fora do runtime

Proposta pendente de implementação:

- Executar migrations em processo temporário separado, com identidade migradora
  sem SUPERUSER e acesso ao histórico Flyway.
- Iniciar a API somente depois de migrations bem-sucedidas; configurar
  `spring.flyway.enabled=false` no runtime e remover dele as credenciais do migrador.
- Manter a conexão da API restrita a DML e o administrador exclusivo do
  provisionamento. Não substituir a identidade runtime pelo migrador.
- Validar inicialização limpa, concorrência entre réplicas, falhas de migration,
  permissões, reinício e atualização de volumes existentes em ambiente isolado.
- Planejar compatibilidade entre aplicação e schema e rollback operacional, sem
  modificar migrations antigas nem apagar volumes. Ensaiar recuperação com o
  [procedimento de backup/restauração](recovery.md) antes de atuar em dados reais.

Até essa etapa ser implementada, o risco de exposição das credenciais DDL por
comprometimento da API permanece.

## Fase 4.4 — Gestão de secrets

Proposta pendente de implementação:

- Escolher armazenamento gerenciado ou arquivos montados com ACLs restritas,
  auditabilidade e menor privilégio. Não presumir que arquivos protegem contra
  administrador do host ou acesso irrestrito ao daemon Docker.
- Integrar o carregamento ao Spring e ao provisionamento PostgreSQL, removendo
  credenciais sensíveis do ambiente dos containers onde viável. Convenções
  `_FILE` não são automaticamente suportadas por todas as propriedades atuais.
- Definir responsáveis, validade, rotação, revogação, retenção e recuperação de
  cada secret. Senhas PostgreSQL exigem coordenação entre roles existentes,
  consumidores e renovação das conexões; mudar um arquivo isoladamente não basta.
- Restringir permissões de `.env`, diretórios temporários, backups e canais do CI;
  preservar relatórios sanitizados e evitar exposição em argumentos e logs.
- Avaliar rotação gradual de JWT para operações planejadas; em comprometimento,
  não manter aceitação da chave antiga. Essa capacidade ainda não existe.

As fases futuras devem preservar autenticação, contratos financeiros e dados;
esta documentação não autoriza sua implementação ou operação em produção.
