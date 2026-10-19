# Proteção de login e cadastro

O Compose publica somente o Nginx em `127.0.0.1:${API_PORT:-8080}`. A API participa das redes internas `backend` e `database`; PostgreSQL participa somente de `database`. O proxy não acessa a rede do banco. Não publique portas da API nem conecte clientes à rede `backend`: isso contornaria o controle de entrada.

## Limites

Configure no `.env`; execute `docker compose up -d --wait` para aplicar mudanças.

| Variável | Padrão | Aplicação |
|---|---:|---|
| `AUTH_LOGIN_RATE` | 10 | Requisições/minuto por IP para POST /auth/login |
| `AUTH_LOGIN_BURST` | 5 | Excesso imediato permitido no login |
| `AUTH_REGISTER_RATE` | 3 | Requisições/minuto por IP para POST /customers |
| `AUTH_REGISTER_BURST` | 2 | Excesso imediato permitido no cadastro |
| `AUTH_TOTAL_RATE` | 600 | Requisições/minuto compartilhadas entre IPs e ambas as rotas |
| `AUTH_TOTAL_BURST` | 30 | Excesso imediato agregado |

Todos os valores devem ser inteiros entre 1 e 60000. Configuração inválida impede a inicialização. O algoritmo leaky bucket libera capacidade continuamente, sem janela fixa por minuto. Uma zona vazia permite uma requisição inicial mais a rajada configurada; todos os limites precisam permitir a requisição. Tentativas válidas e inválidas consomem capacidade. Não há fila de espera: excesso retorna imediatamente HTTP 429 em JSON, `Cache-Control: no-store` e `Retry-After` em segundos.

`Retry-After` é o maior intervalo de reposição entre o limite da rota e o agregado, arredondado para cima; não reserva capacidade e outra tentativa concorrente pode consumi-la. Clientes devem respeitar a espera e usar backoff com jitter. Os demais endpoints e métodos não consomem essas zonas.

## Identidade, caminhos e logs

A chave é o IP da conexão TCP (`$binary_remote_addr`). Não há configuração `real_ip` e os headers Forwarded, X-Forwarded-For, X-Real-IP e demais headers de encaminhamento configurados são removidos antes da API. Spring usa `server.forward-headers-strategy=none`. Alterar Host, query string ou headers de IP não cria nova quota.

O proxy classifica e encaminha o mesmo caminho normalizado, cobrindo barras repetidas e segmentos `.`/`..`. Inclui barra final na proteção. Caminhos com escapes percentuais, parâmetros de matriz (`;`) ou barra invertida são rejeitados com 400; escapes na query string continuam permitidos. As rotas atuais usam segmentos ASCII e UUIDs; uma futura rota com segmentos codificados exigirá revisão dessa política.

O correlation ID deve ser UUID; valores inválidos são substituídos e o ID é propagado à API e à resposta, inclusive no 429. JWT continua no header Authorization. Não há retry automático para outra réplica após falha, evitando replay de operações financeiras pelo proxy.

Logs JSON do proxy contêm somente timestamp, evento, status, duração, resultado do limitador e correlation ID. Não contêm IP, URL, query, corpos, cookies ou credenciais. Logs nativos de erro ficam desabilitados porque podem incluir dados sensíveis; falhas de configuração emitem um evento genérico. Para diagnóstico, consulte health checks, status dos containers e esses metadados. Requisições rejeitadas no proxy não aparecem nas métricas HTTP da API.

## Réplicas e limitações

`docker compose up -d --scale api=2 --wait` mantém um proxy diante de duas APIs, descobertas pelo DNS do Docker. As zonas são compartilhadas entre os workers do mesmo Nginx e limitam o tráfego antes de distribuir às APIs. Não escale o proxy esperando uma quota distribuída: múltiplos proxies precisariam de um mecanismo compartilhado ou de controle no balanceador externo. Recriar o proxy reinicia seus contadores.

Clientes atrás do mesmo NAT compartilham a quota; no Docker Desktop, conexões publicadas podem aparecer com o IP do gateway. Colocar outro proxy à frente também agrupa clientes pelo IP desse proxy. Não habilite confiança em headers para resolver isso sem projetar a cadeia de proxies confiáveis e impedir acesso direto. Esta proteção não substitui defesa contra ataques distribuídos, limites por identidade ou proteção de rede. Um atacante pode consumir a quota agregada e afetar outros clientes.

O Compose permanece local e sem TLS. Executar Java diretamente não aplica estes limites. A rede interna não protege contra um administrador do host/Docker. A base Nginx está fixada por digest; o Dockerfile exige a revisão corrigida de zlib. O build depende do repositório Alpine e pode receber revisões posteriores compatíveis; o digest da base sozinho não fixa todos os pacotes da imagem final. O CI analisa a imagem final do Nginx separadamente com a mesma política de vulnerabilidades da API.

### Validação de segurança da imagem

Em 19/10/2026, Trivy 0.75.0 identificou oito achados bloqueantes na imagem Alpine completa: CVE-2026-77214 e CVE-2026-102633 (libexpat), CVE-2026-88806, CVE-2026-94283, CVE-2026-94284 e CVE-2026-94285 (libx11), CVE-2026-94287 (libxpm) e CVE-2026-85091 (zlib). Nenhum foi dispensado por suposta ausência de exploração.

A [variante oficial slim](https://hub.docker.com/_/nginx/) mantém Nginx 1.30.6 e remove módulos/pacotes de processamento de imagem/XML não utilizados pelo proxy. O Dockerfile atualiza zlib de 1.3.2-r0 para, no mínimo, 1.3.2-r1. A imagem resultante teve 21 pacotes analisados, zero vulnerabilidades reportadas e aprovação pela função existente em `scripts/security/policy.mjs`, sem mudar severidades ou exceções. A cobertura exigida foi de pacotes do sistema; Nginx não contém JARs, ao contrário da imagem Java. Esses resultados são datados e não substituem novas verificações antes de publicação ou atualização.

## Testes direcionados

Requisitos: Docker Linux/Compose v2, Node.js e PowerShell 5.1 no Windows (pwsh no Linux).

```powershell
node scripts/nginx/test-rate-limiting.mjs
```

O runner não usa `.env` nem volumes existentes. Cria credenciais aleatórias, projeto exclusivo, PostgreSQL em tmpfs, duas APIs e dois clientes com IPs TCP distintos. Verifica rajadas, concorrência, reposição, limite agregado, spoofing, variações de URL, isolamento de rede, JSON 429, correlation ID, logs e configuração inválida. Executa também o smoke HTTP existente com os limites padrão. Não executa a regressão completa Maven/Postman.

Recursos são removidos em `finally`, inclusive após falha ou sinal de interrupção tratável. Encerramento forçado do processo/host não permite garantir execução de limpeza; use o nome exclusivo de projeto mostrado pelo runner para inspecionar resíduos, sem remover recursos de outros projetos. Falha de limpeza reprova a execução. Relatórios sanitizados ficam em `target/rate-limiting/summary.json` e `summary.md`, ignorados pelo Git.

A suíte Postman mantém seu ambiente interno separado para validar contratos, sem alterar ou desativar limites do Compose principal. Repetir rapidamente o smoke no mesmo proxy pode consumir sua quota; o runner direcionado usa um projeto novo.
