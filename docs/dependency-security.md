# Dependências e segurança JWT

## Fase 3: auditoria automatizada em 16/10/2026

Trivy 0.75.0, fixado pelo digest
`sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa`,
analisa os pacotes Ubuntu e os JARs da imagem final. Uma segunda análise recebe
um inventário CycloneDX derivado de `maven-dependency-plugin:3.8.1:tree`, incluindo
as 124 coordenadas resolvidas nos escopos de aplicação e teste. O gate exige que
todos esses componentes apareçam no resultado do scanner. Isso evita uma nova
resolução remota do POM, que na validação local recebeu HTTP 429 do Maven Central.
O inventário contém somente coordenadas públicas, sem configurações Maven ou secrets.
Plugins Maven e ferramentas da imagem de build não estão cobertos pelo inventário.

| Componente | Antes | Depois | Motivo |
|---|---|---|---|
| Commons Lang | 3.17.0 | 3.18.0 | CVE-2025-48924: recursão em `ClassUtils.getClass` |
| BOM Log4j / API / bridge SLF4J | 2.24.3 | 2.25.5 | CVE-2026-49844: serialização JSON de `MapMessage` |
| libssl3 e openssl | 3.0.2-0ubuntu1.29 | 3.0.2-0ubuntu1.30 | CVE-2026-84782 e cinco alertas LOW adicionais |
| libfreetype6 | 2.11.1+dfsg-1ubuntu0.3 | 2.11.1+dfsg-1ubuntu0.4 | CVE-2026-95512 |
| libpng16-16 | 1.6.37-3ubuntu0.6 | 1.6.37-3ubuntu0.7 | CVE-2026-46675 |

Foram usados overrides reconhecidos pelo Boot (`commons-lang3.version` e
`log4j2.version`) para a menor versão corrigida escolhida nas mesmas versões
principais. O BOM mantém API e bridge alinhados. Boot 3.5.16, Spring 6.2.19,
Java 17 e Ubuntu 22.04 permanecem. O Docker instala os patches desses pacotes
no repositório Jammy; não faz upgrade de distribuição. Nenhum caminho de exploração
foi demonstrado na API para Commons Lang, MapMessage ou fontes/imagens nativas;
a atualização elimina a dependência desses pressupostos quando há correção segura.

A comparação antes/depois removeu dez ocorrências: as cinco acima e
CVE-2026-35189, CVE-2026-54872, CVE-2026-75805, CVE-2026-75806 e CVE-2026-77696
em libssl3. A imagem passou de 64 para 54 ocorrências: 32 LOW, 20 MEDIUM e
2 CRITICAL segundo o scanner. A análise Maven repete os dois alertas Spring;
o relatório combinado tem 56 linhas, não 56 CVEs únicos.

### Política do gate e exceções

- Erro de Docker, download/base do scanner, JSON inválido, cobertura incompleta,
  interrupção ou falha de limpeza reprova o job e gera erro JUnit. Não há
  `continue-on-error`, fallback para relatório vazio nem `--ignore-unfixed`.
- UNKNOWN, HIGH e CRITICAL bloqueiam, mesmo sem correção disponível. MEDIUM
  com versão corrigida também bloqueia. LOW e MEDIUM sem correção permanecem
  visíveis para acompanhamento; aprovação do gate não significa imagem sem CVEs.
- Apenas CVE-2026-47884 e CVE-2026-47890 em `spring-webmvc:6.2.19` recebem exceção
  de não aplicabilidade até **15/11/2026**. Os avisos Spring classificam os casos
  como MEDIUM e LOW, respectivamente, enquanto o scanner os apresenta CRITICAL.
  O primeiro exige XsltView e resolução específica de view; o segundo exige SSE
  com fragmentos de views e conteúdo controlado por atacante. Controllers REST
  atuais não usam esses recursos. A correção 6.2.20 é comercial; 7.0.9 exigiria
  mudança de versão principal não autorizada.
- A biblioteca Spring **continua em versão afetada**. Não são falsos positivos
  de inventário, nem vulnerabilidades corrigidas. Uma guarda conservadora procura
  referências a views/SSE no código e configuração; novos usos, versão diferente
  ou expiração bloqueiam a exceção. A guarda não substitui revisão humana de
  configuração externa, dependências ou criação dinâmica de beans.

### Risco residual

As 20 ocorrências MEDIUM sem patch Jammy publicado abrangem glibc/locales,
Expat, PCRE2, Cyrus SASL, tar, wget e zlib. O inventário completo permanece no
Markdown do scanner, inclusive os 32 LOW; nada é removido do relatório.

| Grupo | Avaliação no projeto atual |
|---|---|
| glibc: CVE-2026-18374 / CVE-2026-89092 | Requer modo `fopen` controlado externamente ou serviço nscd e DNS hostil; esses caminhos não são configurados pela API. glibc é usada pelo runtime, portanto não se declara ausência geral de risco. |
| PCRE2: CVE-2026-103111, 86145, 89156, 89157, 89158, 89160, 89161 | API usa validações Java, sem entrada de regex nativa ou execução de comandos; os dois casos específicos de 32 bits não correspondem à imagem amd64 validada. Mudanças em código nativo exigem nova análise. |
| Expat: CVE-2025-66382 | Não foi encontrado processamento de XML por Expat a partir de entrada HTTP; biblioteca instalada continua afetada segundo o scanner. |
| Cyrus SASL: CVE-2026-107161 | Exige cliente DIGEST-MD5 conectado a servidor hostil; autenticação da API é JWT e não configura esse cliente. Ubuntu informa correção adiada; seu CVSS é HIGH, embora a prioridade Ubuntu/Trivy seja MEDIUM. |
| tar: CVE-2026-18477 / CVE-2026-18508; wget: CVE-2021-31879 | API não executa extração tar nem wget a partir de requisições. O ensaio usa pg_dump/pg_restore no container PostgreSQL, não GNU tar da API. Não utilizar esses utilitários com entradas hostis. |
| zlib: CVE-2026-85091 | Descrição upstream menciona versões diferentes da instalada; possível divergência de classificação, ainda sem confirmação suficiente do fornecedor. Mantido como risco residual, sem supressão por falso positivo. |

Não foi confirmado falso positivo suficiente para excluir qualquer alerta.
Essa aceitação técnica é limitada ao estado atual do projeto e à política acima;
revisar antes da release, ao adicionar funcionalidades e até 15/11/2026. Não é
certificação para produção nem autorização para ignorar avisos futuros.

### Reprodução

```powershell
.\mvnw.cmd -B -ntp dependency:tree '-DoutputType=json' '-DoutputFile=target/dependency-tree.json'
node scripts/security/sbom.mjs
docker build --pull -t banking-security-local .
$imageId = docker image inspect banking-security-local --format '{{.Id}}'
node scripts/security/scan.mjs $imageId
```

Em Linux use `./mvnw`. Não execute `mvn clean` em paralelo ao scanner: ambos usam
`target/`. Os relatórios permitidos são `target/security/junit.xml` e `summary.md`.
O scanner não monta o socket Docker; lê um arquivo exportado em diretório privado.
Esse arquivo e o JSON bruto são removidos em `finally`. Cache sem credenciais fica
em `target/security-cache`; a imagem analisada e a imagem da ferramenta são preservadas.
As bases são atualizadas pelo comportamento padrão do Trivy, sem desativar downloads.

Os arquivos legados `.git/phase3-validation/api-image.tar` (151991296 bytes) e
a imagem `banking-phase3-audit-api` permanecem locais. A limpeza anterior foi
bloqueada por política e não foi contornada. A imagem Trivy é ferramenta local;
nenhum desses recursos é versionado ou contém o banco de desenvolvimento.

Fontes: [Commons Lang](https://commons.apache.org/proper/commons-lang/changes.html),
[Log4j](https://logging.apache.org/security.html),
[OpenSSL no Ubuntu](https://ubuntu.com/security/CVE-2026-84782),
[Cyrus SASL no Ubuntu](https://ubuntu.com/security/CVE-2026-107161),
[Trivy SBOM](https://trivy.dev/docs/latest/target/sbom/),
[Maven dependency tree](https://maven.apache.org/plugins/maven-dependency-plugin/examples/tree-mojo.html).

## Histórico: dependências e JWT da Etapa 1

Revisão em 14/10/2026. Escopo: dependências indicadas na auditoria e validação da
configuração JWT, sem alteração de contratos REST, migrations ou regras financeiras.

## Versões e compatibilidade

| Componente | Antes | Depois |
|---|---|---|
| Spring Boot | 3.5.16 | 3.5.16 |
| Spring Framework / MVC | 6.2.19 | 6.2.19 |
| Tomcat embarcado (core, EL e WebSocket) | 10.1.55 | 10.1.61 |
| PostgreSQL JDBC | 42.7.11 | 42.7.14 |
| Jackson BOM, core, databind e módulos | 2.21.4 | 2.21.7 |
| Jackson annotations | 2.21 | 2.21 |
| JJWT | 0.13.0 | 0.13.0 |

O Maven Central não oferece um patch 3.5 posterior a 3.5.16 nesta data. Foram
sobrescritas somente as propriedades `tomcat.version`, `postgresql.version` e
`jackson-bom.version` reconhecidas pelo parent. São patches nas mesmas linhas já
utilizadas pelo Boot; não houve migração para Boot 4, Spring 7 ou Jackson 3.
O BOM Jackson mantém os módulos alinhados, inclusive a versão própria de annotations.
Compatibilidade deve ser verificada pela suíte Maven e pelo teste Compose; essas
substituições não equivalem a um novo BOM oficialmente publicado pelo Spring Boot.
Reavaliar/remover os overrides quando um parent adotado incorporar as correções.

## Alertas e decisão

- **Spring MVC:** CVE-2026-47890 depende de SSE com fragmentos de views;
  CVE-2026-47884 depende de `XsltView` e resolução específica de views. A API usa
  controllers REST e não configura esses recursos. A correção 6.2.20 é oferecida
  pelo suporte comercial, não pelo Maven Central. Manter 6.2.19 nesta etapa é uma
  exceção de aplicabilidade, não uma afirmação de que a biblioteca foi corrigida.
  Rever antes de introduzir views/XSLT/SSE ou ao planejar a evolução do framework.
- **Tomcat:** os alertas iniciais sobre DIGEST, FORM e constraints do container não
  correspondem à autenticação Spring Security/JWT usada aqui. A página do mantenedor
  também lista correções posteriores para HTTP/2, WebSocket, AJP e parsing HTTP.
  Adotado 10.1.61, patch estável disponível, incluindo as correções de 10.1.60.
- **PostgreSQL JDBC:** GHSA-j92g-9f8w-j867 afeta a garantia de
  `channelBinding=require`, não configurada no repositório. A correção existe desde
  42.7.12; adotado o patch estável 42.7.14 da mesma linha, incluindo correções posteriores.
  O patch 42.7.14 também corrige CVE-2026-107314 (`requireAuth`) e CVE-2026-107315
  (preenchimento de streams com dados de mensagens anteriores).
  A configuração externa de produção deve ser considerada ao avaliar aplicabilidade.
- **Jackson:** os alertas consultados dependem de combinações de `JsonView`,
  `JsonUnwrapped`, `JsonIgnoreProperties`, polimorfismo, `JsonIdentityInfo` ou tipos
  como `InetAddress`, `Path`, `Duration` XML e `XMLGregorianCalendar`. Esses usos não
  foram encontrados nos contratos de entrada. O BOM 2.21.7 incorpora as correções
  disponíveis na mesma linha, evitando depender permanentemente dessa ausência.

Os nove avisos Jackson triados foram GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v,
GHSA-cxp5-3px4-pw24, GHSA-gx83-3vf8-gh7j, GHSA-mhm7-754m-9p8w,
GHSA-q4xh-88c3-wmh7, GHSA-vvgp-rfg2-7rr6, GHSA-wjgm-6hv5-3cvf e
GHSA-wv8q-qhhj-9h54. A análise é direcionada; não certifica ausência de outras
vulnerabilidades na aplicação, nas dependências transitivas ou na imagem de base.

## Configuração JWT

`JwtService` valida sua configuração durante a criação do bean, antes de atender
requisições. A chave é obrigatória, não pode ser branca e deve ter pelo menos
32 bytes UTF-8, mínimo para HS256. A seleção existente de HS256/HS384/HS512 pela
biblioteca é preservada para chaves maiores. Não há nova codificação do segredo.

`JWT_EXPIRATION` continua em milissegundos e com padrão de 86400000; valores fora
de 1..86400000 impedem a inicialização. O teto de 24 horas mantém o padrão atual,
limita exposição sem revogação individual e impede overflow por duração excessiva.
Configurações anteriores acima de 24 horas precisam ser reduzidas antes do deploy.
O teto afeta a emissão de novos tokens; não invalida antecipadamente tokens antigos
corretamente assinados. Mensagens de validação não incluem a chave.

`JwtConfigurationTest` verifica inicialização válida/inválida, limites, segredo em
bytes, compatibilidade HMAC e ausência da chave na cadeia de erros.
`JwtServiceTest` verifica geração, validade, expiração e adulteração. As suítes HTTP
existentes verificam autenticação e as operações reais sobre PostgreSQL.

## Validação local

- Testes unitários selecionados: 77 aprovados.
- Integração, segurança e HTTP: 222 aprovados em PostgreSQL 16 temporário.
- `mvnw verify`: 299 testes, zero falhas, erros ou ignorados; inclui novamente os
  grupos anteriores, não representa 598 testes distintos.
- A suíte de endpoints realizou 318 requisições HTTP reais e verificou as 18 rotas
  de negócio documentadas.
- Docker Compose: imagem construída, serviços saudáveis e 15 requisições do
  script `Test-Compose.ps1` aprovadas, com PostgreSQL 17.

Os bancos foram isolados e descartados após os testes. Não foi executado deploy
remoto, teste de carga ou análise exaustiva de segurança. O CI remoto deve validar
novamente a mudança quando ela for submetida para revisão.

## Fontes

- [BOM Spring Boot 3.5.16](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom)
- [Spring: CVE-2026-47890](https://spring.io/security/cve-2026-47890/)
- [Spring: CVE-2026-47884](https://spring.io/security/cve-2026-47884/)
- [Segurança Tomcat 10](https://tomcat.apache.org/security-10.html)
- [Changelog Tomcat 10.1](https://tomcat.apache.org/tomcat-10.1-doc/changelog.html)
- [Aviso pgJDBC](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-j92g-9f8w-j867)
- [Changelogs pgJDBC](https://jdbc.postgresql.org/changelogs/)
- [Release Jackson 2.21.7](https://github.com/FasterXML/jackson-databind/releases/tag/jackson-databind-2.21.7)
- [Avisos Jackson](https://github.com/FasterXML/jackson-databind/security/advisories)
