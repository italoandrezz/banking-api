# Banking API v1.0.0 — candidato à release

Status: preparado para avaliação, ainda não publicado. A versão Maven é `1.0.0`;
isso não representa aprovação para produção, tag criada ou release publicada.

## Versões validadas

| Componente | Versão |
|---|---|
| Java | 17 |
| Spring Boot / Framework | 4.0.8 / 7.0.9 |
| Tomcat | 11.0.27 |
| Jackson 3 / Jackson 2 | 3.1.7 / 2.21.7 (annotations 2.21) |
| Nginx / Alpine | 1.30.6 / 3.24.3 |
| musl / musl-utils | 1.2.6-r3 |

## Funcionalidades e arquitetura

- API REST Java 17/Spring Boot com cadastro de clientes, autenticação JWT e
  autorização por titularidade e perfil administrativo.
- Contas, bloqueio/desbloqueio, encerramento, depósitos, saques, transferências,
  histórico financeiro e estornos administrativos.
- Transações, locks e idempotência protegem as movimentações e seus replays.
- PostgreSQL com identidades distintas de administrador, migrador e runtime DML.
  Flyway executa no serviço temporário `migrate`, na mesma imagem da API, fora do
  contexto Spring; advisory lock serializa migrations e ajustes de permissões.
  Falha do migrador impede a inicialização da API.
- Nginx como entrada publicada, com limites separados e agregados para login e
  cadastro; IP da conexão TCP como referência, sem confiar em headers do cliente.

## Segurança e observabilidade

Secrets individuais são montados somente para leitura e por serviço autorizado.
A API recebe senha DML e JWT, o migrador recebe somente sua senha e o banco recebe
as três senhas de provisionamento. Nginx não recebe secrets. A configuração por
arquivo é validada antes da inicialização; valores não entram na imagem ou Git.

Correlation ID, logs JSON, liveness/readiness e tratamento seguro de erros 500/503
completam a observabilidade. Documentação Swagger/OpenAPI é desabilitada no profile
`prod`; endpoints administrativos permanecem protegidos. A política Trivy reprova
falhas do scanner e cobertura incompleta, além dos achados bloqueantes.

## Evidências de validação

A migração foi integrada pelo [PR #47](https://github.com/italoandrezz/banking-api/pull/47),
merge `dc6f8fb44d2acb2971221f4a87af0335f6ea6eb0`, com
[CI aprovado](https://github.com/italoandrezz/banking-api/actions/runs/39855679350):
`build` e `Docker Compose validation`. São evidências da migração integrada,
não uma nova execução sobre o candidato com versão Maven `1.0.0`.

| Validação | Evidência existente |
|---|---|
| Maven | CI aprovado; regressão local de 357 testes teve um erro de fixture configtree, corrigido e revalidado nos três testes afetados; depois, 89 testes direcionados Tomcat e 68 Jackson aprovados |
| Node.js | 53 testes locais aprovados; infraestrutura Postman aprovada no CI |
| Postman | 185 requisições e 1.047 assertions locais aprovadas; collection também aprovada no CI da migração |
| Recuperação e isolamento | Evidências locais da migração: 7 verificações de recuperação, 3 de migrations e 2 de rotação aprovadas |
| Compose/Nginx | CI aprovado; 12 verificações direcionadas Nginx; smoke da imagem final com duas APIs, 15 requisições funcionais e 12 verificações HTTP, limpeza confirmada |
| Trivy | API, inventário Maven (154 coordenadas) e Nginx aprovados pela política vigente, localmente e no CI |

O ensaio cobre backup/restauração em banco novo, dados e permissões preservados,
replay após reinício e indisponibilidade, rotação JWT e rejeição da senha DML antiga.
Os relatórios sanitizados do CI têm retenção limitada e devem ser preservados pelo
responsável da release antes de expirar. Relatórios brutos e secrets não são artifacts.

### Validação final local do candidato 1.0.0

Relatórios existentes confirmam 357 testes Java sem falhas, erros ou ignorados,
53 testes Node.js aprovados e Postman com 185 requisições e 1.047 assertions sem
falhas. Recuperação (7 verificações), migrations (3) e rotação (2) passaram, com
limpeza confirmada. O wrapper PowerShell tratou um aviso stderr da JVM como erro;
o verify posterior com testes dispensados confirmou BUILD SUCCESS e empacotamento,
sem repetir os testes já aprovados. Trivy aprovou API, Maven (154 coordenadas) e
Nginx com a política vigente; as 13 CVEs acompanhadas não apareceram nos scans,
e permaneceram 42 ocorrências residuais não bloqueantes na API.

No Nginx, nove cenários passaram inicialmente; o cenário de reposição da quota
falhou. Apenas ele e os dois cenários pendentes foram executados depois, com três
aprovações e limpeza confirmada. A sensibilidade temporal observada fica como
acompanhamento não bloqueante, sem atribuir causa não comprovada. Não houve
alteração dos limites, das assertions ou da política para obter aprovação.
O resumo detalhado está em target/release-validation-summary.md, fora do Git.
Esta conferência dos relatórios não executou novas validações.

## Segurança: resultado atual e histórico

As **13 CVEs acompanhadas não aparecem nos scans finais**: três Tomcat, sete
Jackson, uma musl e as Spring CVE-2026-47884 e CVE-2026-47890. A lista completa,
versões e decisões estão em [segurança de dependências](dependency-security.md).
A API mantém **42 ocorrências residuais não bloqueantes** no sistema operacional,
não necessariamente 42 CVEs distintas. Nginx não apresentou achados no scan local
final. PASS significa atendimento à política vigente, não ausência absoluta de
vulnerabilidades. Nenhuma exceção ou critério foi flexibilizado.

Antes da migração, o candidato usava Boot 3.5.16 / Framework 6.2.19 e as duas CVEs
Spring permaneciam pendentes. Em 21/10/2026, não havia patch OSS compatível
confirmado nessa linha; foram avaliados Framework 6.2.20 comercial e migração OSS
para Boot 4.0.8 / Framework 7.0.9. A segunda alternativa foi implementada e validada.
As exceções históricas limitadas a spring-webmvc:6.2.19 continuam registradas,
com vencimento em **15/11/2026 às 00:00 UTC**, sem prorrogação; não foram necessárias
para aprovar o inventário atual. Rollback para versões afetadas exige reavaliação.

A decisão de publicação permanece pendente. O responsável deve registrar a análise
dos riscos residuais, responsáveis e acompanhamento, além de validar a configuração
de destino. CI aprovado não autoriza automaticamente produção.

## Execução do candidato

Execute os comandos a partir da raiz deste worktree. O artefato Maven da release
é `target/banking-api-service-1.0.0.jar`. Para execução Java direta, conclua primeiro
as migrations com PropertiesLauncher, conforme [migrations](migrations.md), em
processo com identidade DDL separada. Só então inicie a API com credenciais DML,
JWT e profile de produção configurados; nunca herde o ambiente do migrador.
No Compose, mantenha a dependência de sucesso do serviço `migrate`.

Os comandos `node scripts/postman/run.mjs` e
`node scripts/postman/run.mjs --recovery` continuam usando ambientes descartáveis.
Ao usar `--image`, forneça o ID imutável da imagem construída para este candidato,
não uma imagem antiga. Consulte [Postman](postman-testing.md) e
[recuperação](recovery.md) para requisitos, isolamento e limpeza.

## Checklist operacional de produção

Preencher responsáveis e evidências no registro de implantação, sem valores secretos.
Todos os itens abaixo permanecem pendentes para o ambiente real.

- [ ] Release: indicar responsável pela aprovação, commit/digest exatos e notas finais;
  aprovar os checks do candidato antes de publicação.
- [ ] Segurança: registrar avaliação das 42 ocorrências residuais, responsáveis e prazos de acompanhamento.
- [ ] Infraestrutura: configurar TLS, certificados, renovação e encaminhamento de IP
  compatível com o Nginx; validar que somente a entrada autorizada está publicada.
- [ ] Aplicação: ativar explicitamente `prod`, confirmar Swagger/OpenAPI indisponíveis,
  healthchecks funcionais e endpoints administrativos protegidos.
- [ ] Secrets: designar custodiante, preparar arquivos/ACLs, inventário e rotação;
  preservar valores atuais na primeira adoção e nunca reativar chaves comprometidas.
- [ ] Banco: designar operador; confirmar papéis, privilégios mínimos, backup prévio,
  compatibilidade do schema e execução de um migrador novo com saída zero por deploy.
- [ ] Backups: definir agendamento, retenção, criptografia, acesso e cópias fora do host;
  designar responsável e registrar ensaio de restauração em cluster novo.
- [ ] Recuperação: aprovar RPO e RTO mensuráveis, medir restauração e reconciliar escritas
  posteriores ao backup antes de qualquer troca de banco.
- [ ] Operação: designar responsável por alertas e incidentes, janela de manutenção,
  smoke pós-deploy e rollback com imagem anterior preservada por digest.

Procedimentos: [migrations](migrations.md), [secrets](secrets.md),
[recuperação](recovery.md) e [observabilidade](observability.md).

## Limitações conhecidas

Não há certificação de produção, teste de carga do ambiente final ou comprovação de
seu TLS e backups reais. O ensaio de recuperação usa bancos descartáveis, não prova
restauração entre versões principais do PostgreSQL. Não execute restauração sobre
banco existente nem remova volumes para contornar falhas.

Secrets Compose são arquivos, não um cofre criptografado; administradores do host
podem acessá-los. Rotação exige coordenação e recriação das APIs, sem recarga
automática ou sobreposição de chaves JWT. O bootstrap PostgreSQL pode usar valores
transitórios no ambiente interno. Limpeza pode exigir intervenção após perda do host.

Os limites Nginx são compartilhados pelos workers de um mesmo proxy, não por proxies
independentes; NAT pode agrupar clientes. Escala e topologia de proxies exigem revisão.
Rollback após novas escritas exige reconciliação financeira; voltar apenas a imagem
ou a um backup antigo não garante recuperação segura.
