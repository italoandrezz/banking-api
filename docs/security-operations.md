# Segurança operacional

Este documento registra procedimentos e pendências. Não executa rotação, migrations,
alteração de credenciais ou implantação. A separação Flyway e os secrets por arquivo
seguem os guias de [migrations](migrations.md) e [secrets](secrets.md).

## Rotação emergencial do JWT

A API usa uma única chave HMAC em `jwt.secret` (arquivo no Compose; `JWT_SECRET` no modo legado), carregada ao iniciar o processo.
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
   atualize somente o arquivo `jwt_secret` no diretório privado, com permissões restritas,
   sem imprimir o valor, colocá-lo em argumentos de comandos, histórico, tickets
   ou relatórios. Não copie `.env.example` sobre um `.env` preenchido.
4. Recrie todas as instâncias da API para carregar o novo arquivo; em processos
   Java fora do Compose, reinicie-os com o ambiente atualizado. `docker compose
   restart` não garante a troca de inode em mounts de arquivos substituídos.
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
  foram substituídos por `SECRETS_DIR`. `.env` é excluído pelas regras locais do Git e pelo contexto
  de build da API. Isso não remove segredos eventualmente publicados no passado
  nem protege cópias, backups ou permissões locais excessivas.
- Administrador, migrador e aplicação usam credenciais distintas. O `.env` não
  altera automaticamente senhas de roles em volumes existentes; siga o
  [procedimento PostgreSQL](postgresql-security.md).
- A API não recebe credenciais do Flyway. Elas ficam no bootstrap do banco e no
  serviço temporário `migrate`, montadas por arquivo. Os arquivos continuam acessíveis a quem
  administra o Docker. Evite publicar saídas de `docker inspect` ou de
  `docker compose config` sem sanitização.
- A chave fixa e as credenciais de fixtures pertencem exclusivamente aos testes.
  Não as reutilize em produção. O profile `prod` desabilita documentação, mas não
  fornece um gerenciador de secrets. Flyway é separado independentemente do profile.
- Para ativar `prod`, o profile deve chegar ao processo Spring. Adicionar
  `SPRING_PROFILES_ACTIVE` somente ao `.env` não o transmite automaticamente pelo
  Compose atual, que não declara essa variável. Configuração de implantação
  explícita continua necessária; este documento não altera o Compose.

## CVEs Spring e prazo das exceções

Na migração atual, Spring Boot 4.0.8 utiliza Framework 7.0.9. As CVEs
CVE-2026-47884 e CVE-2026-47890 não aparecem nos scans locais atuais.
O [guia de dependências](dependency-security.md) registra as versões, as 13 CVEs
acompanhadas e as 42 ocorrências residuais não bloqueantes na imagem API.
Aprovação do Trivy pela política vigente não equivale a ausência de riscos.

Historicamente, as exceções foram concedidas somente para spring-webmvc:6.2.19,
que permanecia afetado. Essa decisão foi preservada; as exceções não foram
necessárias para aprovar o inventário atual. Nenhuma regra foi alterada.
Em scripts/security/policy.mjs, o vencimento permanece em **15/11/2026 às 00:00 UTC**
(14/11/2026 às 21:00 em Fortaleza). Não prorrogar automaticamente nem reduzir
severidade para aprovar o CI. Rollback para uma versão afetada exige reavaliação,
inclusive das condições de aplicabilidade e do prazo original.

### Histórico: alternativas avaliadas em 21/10/2026

Naquele candidato, CVE-2026-47884 e CVE-2026-47890 ainda não estavam corrigidas.
Na avaliação de 21/10/2026, não foi confirmado patch OSS compatível com a linha
Spring Boot 3.5.16; o projeto utilizava Spring Framework 6.2.19.

- **Alternativa comercial:** Spring Framework 6.2.20, condicionada ao acesso ao
  repositório/suporte comercial e à validação de compatibilidade com Boot 3.5.16,
  Java 17 e as demais dependências. A atualização não foi aplicada.
- **Plano de correção OSS:** migração para Spring Boot 4.0.8 / Framework 7.0.9,
  sujeita a autorização específica, revisão das dependências e testes de regressão.
  Java 17 é suportado, mas a mudança principal não é uma substituição direta
  de Framework 6.2 por 7.0 no Boot atual. Naquela data, o plano ainda não havia sido implementado.

As exceções expiram em **15/11/2026 às 00:00 UTC**, sem prorrogação.
**A decisão de publicação permanece pendente, sem aprovação implícita de risco.**
Esta documentação não autoriza deploy nem declara o projeto seguro para produção;
passar no CI não substitui a decisão explícita sobre os riscos residuais.

Fontes: [CVE-2026-47884](https://spring.io/security/cve-2026-47884/),
[CVE-2026-47890](https://spring.io/security/cve-2026-47890/) e
[requisitos do Boot 4.0](https://docs.spring.io/spring-boot/4.0/system-requirements.html).

Atualização após o PR #47: a alternativa OSS foi implementada e integrada em
`dc6f8fb44d2acb2971221f4a87af0335f6ea6eb0`. As duas CVEs Spring, junto às demais
11 acompanhadas, não aparecem nos scans finais. A política e os prazos históricos
foram preservados; a decisão de publicação e o tratamento dos riscos residuais
continuam sujeitos à aprovação explícita.

## Fase 4.3 — Flyway fora do runtime

Implementação operacional descrita no [guia de migrations](migrations.md):

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

A separação reduz o impacto de comprometimento da API. Administradores Docker e
o processo de provisionamento ainda têm acesso aos segredos; remover o container
encerrado do migrador reduz sua retenção, sem substituir a Fase 4.4.

## Fase 4.4 ? Gestão de secrets

Implementada por arquivos privados e montagens por serviço, sem novas dependências.
Consulte [secrets](secrets.md) para ACLs, configuração, adoção, rotação, rollback
e limites de proteção contra administradores do host/Docker. Um cofre externo e
rotação gradual com múltiplas chaves JWT continuam fora deste escopo.
