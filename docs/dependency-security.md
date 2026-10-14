# Dependências e segurança JWT

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
