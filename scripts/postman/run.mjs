import { spawn } from 'node:child_process';
import { randomBytes, randomUUID, createHmac } from 'node:crypto';
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { sanitizedReport, junitReport } from './report.mjs';
import { verifyRecovery, discoverHealthyApi } from './recovery.mjs';
import { verifyMigrationIsolation } from './migration-isolation.mjs';
import { writeTestSecrets, verifySecretMounts, isTestSecretBind } from './secret-files.mjs';
import { verifySecretRotation } from './secret-rotation.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const inputArgs = process.argv.slice(2);
const imageIndex = inputArgs.indexOf('--image');
let borrowedImage;
if (imageIndex >= 0) {
  borrowedImage = inputArgs[imageIndex + 1];
  if (!/^sha256:[a-f0-9]{64}$/.test(borrowedImage || '')) throw new Error('Expected a local immutable image ID');
  inputArgs.splice(imageIndex, 2);
}
const args = new Set(inputArgs);
if ([...args].some(a => !['--interactive', '--simulate-failure', '--recovery'].includes(a))) {
  throw new Error('Accepted options: --image <local ID>, --interactive, --simulate-failure, --recovery; arbitrary API targets are forbidden.');
}
const project = 'banking-postman-' + randomBytes(8).toString('hex');
const outputDir = path.join(root, 'target/postman', project);
const privateDir = await mkdtemp(path.join(tmpdir(), 'banking-postman-private-'));
const envFile = path.join(privateDir, 'empty.env');
await writeFile(envFile, '');
const dbPassword = randomBytes(32).toString('hex');
const jwtSecret = randomBytes(32).toString('hex');
const testEnv = { ...process.env, POSTMAN_TEST_DB_PASSWORD: dbPassword, POSTMAN_TEST_JWT_SECRET: jwtSecret };
testEnv.POSTMAN_TEST_ADMIN_PASSWORD = randomBytes(32).toString('hex');
testEnv.POSTMAN_TEST_MIGRATOR_PASSWORD = randomBytes(32).toString('hex');
testEnv.POSTMAN_TEST_API_IMAGE = borrowedImage || `${project}-api`;
// Neither a cloud API key nor a proxy is needed for a loopback-only run.
for (const key of Object.keys(testEnv)) {
  if (/^(POSTMAN_API_KEY|POSTMAN_ACCESS_TOKEN|HTTP_PROXY|HTTPS_PROXY|ALL_PROXY)$/i.test(key)) delete testEnv[key];
}
testEnv.NO_PROXY = 'localhost,127.0.0.1';
testEnv.POSTMAN_DISABLE_TELEMETRY = 'true';
const composeArgs = ['compose', '--project-name', project, '--env-file', envFile, '-f', path.join(root, 'compose.postman.yaml')];
let child;
let interrupted = false;
let cleaning = false;
let stage = 'prerequisites';
let exitCode = 1;
let summary = { project, timestamp: new Date().toISOString(), success: false, requests: 0, assertions: 0, failedAssertions: 0, executions: [], endpoints: [], cleanup: false };
let releaseInteractive;
const onSignal = () => {
  interrupted = true;
  if (!cleaning) child?.kill('SIGTERM');
  releaseInteractive?.();
};
process.on('SIGINT', onSignal);
process.on('SIGTERM', onSignal);

// Capture native output in memory. Never echo raw HTTP bodies, SQL, env or native reports.
async function command(program, arguments_, { input, timeout = 180_000, allowFailure = false, env = testEnv } = {}) {
  if (interrupted && !cleaning) throw new Error('Interrupted');
  return new Promise((resolve, reject) => {
    const proc = spawn(program, arguments_, { cwd: root, env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    child = proc;
    let stdout = '', stderr = '', timedOut = false;
    const timer = setTimeout(() => { timedOut = true; proc.kill('SIGTERM'); }, timeout);
    proc.stdout.on('data', chunk => { stdout += chunk; });
    proc.stderr.on('data', chunk => { stderr += chunk; });
    proc.on('error', () => { clearTimeout(timer); reject(new Error(`Unable to start ${path.basename(program)}`)); });
    proc.on('close', code => {
      clearTimeout(timer);
      if (child === proc) child = undefined;
      if (timedOut || (interrupted && !cleaning)) reject(new Error(timedOut ? 'Command timeout' : 'Interrupted'));
      else if (code !== 0 && !allowFailure) reject(new Error(`${path.basename(program)} failed during ${stage}`));
      else resolve({ code, stdout, stderr });
    });
    proc.stdin.on('error', () => {});
    proc.stdin.end(input);
  });
}
const compose = (arguments_, options) => command('docker', [...composeArgs, ...arguments_], options);
async function findCli() {
  const candidates = process.platform === 'win32' ? [
    path.join(process.env.LOCALAPPDATA || '', 'Programs/PostmanCLI/postman.exe'),
    path.join(process.env.LOCALAPPDATA || '', 'Microsoft/WindowsApps/postman.exe'), 'postman.exe'
  ] : ['postman'];
  for (const candidate of candidates) {
    try { const result = await command(candidate, ['--version']); return { program: candidate, version: result.stdout.trim() }; }
    catch { /* Try the next official installation location. */ }
  }
  throw new Error('Postman CLI is missing. Install it using the official instructions.');
}
async function api(baseUrl, method, route, body) {
  const response = await fetch(baseUrl + route, { method, redirect: 'error', signal: AbortSignal.timeout(20_000),
    headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  if (!response.ok) throw new Error(`Fixture HTTP ${response.status}`);
  return response.json();
}
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
async function saveReports() {
  await mkdir(outputDir, { recursive: true });
  const totals = `${summary.requests} requisicoes; ${summary.assertions} assertions; ${summary.failedAssertions} assertions reprovadas.`;
  const rows = summary.endpoints.map(e => `| ${e.endpoint} | ${e.requests} | ${e.passed} | ${e.failed} |`).join('\n');
  const failures = summary.executions.filter(e => !e.passed).map(e => `- ${e.name}: HTTP esperado ${e.expectedStatus}, recebido ${e.status ?? 'sem resposta'}; assertions: ${e.assertions.filter(a => !a.passed).map(a => a.name).join(', ') || 'verificar script/status'}.`).join('\n');
  const markdown = `# Postman - Banking API\n\nResultado: **${summary.success ? 'PASS' : 'FAIL'}**\n\n${totals}\n\nPostman CLI: ${summary.cliVersion || 'indisponivel'}.\n\nLimpeza automatica: ${summary.cleanup ? 'confirmada' : 'nao confirmada'}.\n\n${summary.infrastructureFailure ? 'Falha de infraestrutura na etapa: ' + summary.infrastructureFailure + '.\n\n' : ''}${summary.applicationFailure ? 'Falha de verificacao da aplicacao na etapa: ' + summary.applicationFailure + '.\n\n' : ''}| Endpoint | Requisicoes | Aprovadas | Reprovadas |\n|---|---:|---:|---:|\n${rows}\n\n## Falhas\n\n${failures || (summary.success ? 'Nenhuma.' : 'Execucao incompleta ou falha de infraestrutura.')}\n\nRelatorio sem tokens, credenciais, identificadores de clientes, headers ou corpos HTTP.\n`;
  const html = `<!doctype html><html lang="pt-BR"><meta charset="utf-8"><title>Banking API - Postman</title><style>body{font:16px system-ui;max-width:1100px;margin:40px auto}table{border-collapse:collapse;width:100%}td,th{padding:8px;border:1px solid #ccc}summary{cursor:pointer}pre{white-space:pre-wrap}</style><h1>Postman: ${summary.success ? 'PASS' : 'FAIL'}</h1><p>${totals}</p><p>Limpeza: ${summary.cleanup ? 'confirmada' : 'nao confirmada'}</p>${summary.executions.map(e => `<details><summary>${e.passed ? 'PASS' : 'FAIL'}: ${escape(e.name)} — HTTP ${e.status ?? 'sem resposta'}</summary><ul>${e.assertions.map(a => `<li>${a.passed ? 'PASS' : 'FAIL'}: ${escape(a.name)}</li>`).join('')}</ul></details>`).join('')}</html>`;
  const junit = junitReport(summary);
  const recoveryMarkdown = summary.recovery ? '\n## Recuperacao operacional\n\n' + summary.recovery.checks.map(name => '- PASS: ' + name).join('\n')
    + '\n\nRegistros restaurados e comparados integralmente: ' + Object.entries(summary.recovery.rows).map(([table, count]) => `${table}: ${count}`).join('; ') + '.\n' : '';
  const latest = path.join(root, 'target/postman/latest');
  await mkdir(latest, { recursive: true });
  for (const [filename, content] of Object.entries({ 'summary.json': JSON.stringify(summary, null, 2) + '\n', 'summary.md': markdown + recoveryMarkdown, 'report.html': html, 'junit.xml': junit })) {
    const value = filename === 'summary.md' ? content + '\n## Migration isolation and secret rotation\n\n' + [...(summary.migrationChecks || []), ...(summary.secretChecks || [])].map(name => '- PASS: ' + name).join('\n') + '\n' : content;
    await writeFile(path.join(outputDir, filename), value);
    await writeFile(path.join(latest, filename), value);
  }
}

try {
  testEnv.POSTMAN_TEST_SECRETS_DIR = path.join(privateDir, 'secrets');
  await writeTestSecrets(testEnv.POSTMAN_TEST_SECRETS_DIR, { admin_password: testEnv.POSTMAN_TEST_ADMIN_PASSWORD,
    app_password: dbPassword, migrator_password: testEnv.POSTMAN_TEST_MIGRATOR_PASSWORD, jwt_secret: jwtSecret });
  const cli = await findCli();
  summary.cliVersion = cli.version;
  const help = await command(cli.program, ['collection', 'run', '--help']);
  if (!help.stdout.includes('--no-report-events')) throw new Error('Postman CLI must support --no-report-events');
  await command('docker', ['info', '--format', '{{.ServerVersion}}']);
  const collectionFile = path.join(root, 'postman/banking-api.postman_collection.json');
  const collection = JSON.parse(await readFile(collectionFile, 'utf8'));
  summary.expectedRequests = collection.item.flatMap(folder => folder.item).length;
  if (collection.info._postman_id) throw new Error('Use an unlinked local collection');
  console.log(`Postman CLI ${cli.version}; isolated Docker project ${project}.`);
  stage = 'build';
  if (borrowedImage) {
    const inspected = await command('docker', ['image', 'inspect', borrowedImage, '--format', '{{.Id}}']);
    if (inspected.stdout.trim() !== borrowedImage) throw new Error('Local image mismatch');
    console.log('Reusing verified local API image.');
  } else {
    console.log('Building test API image...');
    await compose(['build'], { timeout: 900_000 });
  }
  // Unique tmpfs stack: prove the dependency gate before creating any fixtures.
  stage = 'migration-failure-gate';
  await compose(['up', '-d', '--wait', '--wait-timeout', '90', 'db']);
  const failingMigration = path.join(privateDir, 'migration-failure.json');
  await writeFile(failingMigration, JSON.stringify({ services: { migrate: { environment: {
    FLYWAY_PASSWORD_FILE: '/run/secrets/missing'
  } } } }), { mode: 0o600 });
  const gate = await command('docker', [...composeArgs, '-f', failingMigration,
    'up', '-d', '--no-build', '--wait', '--wait-timeout', '60', 'api'], { allowFailure: true });
  if (gate.code === 0) throw new Error('Failed migration did not block startup');
  if ((await compose(['ps', '--status', 'running', '-q', 'api'])).stdout.trim())
    throw new Error('API started despite failed migration');
  const failedId = (await compose(['ps', '--all', '-q', 'migrate'])).stdout.trim();
  const failedState = JSON.parse((await command('docker', ['inspect', failedId])).stdout)[0];
  if (failedState.State.Status !== 'exited' || failedState.State.ExitCode !== 1)
    throw new Error('Expected migration process failure was not observed');
  await compose(['rm', '-f', 'api', 'migrate']);
  summary.migrationChecks = ['Failed migration prevents API startup'];
  console.log('Failed migration blocks API startup: verified.');
  stage = 'startup';
  console.log('Starting disposable PostgreSQL, migrator and API...');
  await compose(['up', '-d', '--no-build', '--wait', '--wait-timeout', '180'], { timeout: 210_000 });
  const serviceJson = (await compose(['ps', '--all', '--format', 'json'])).stdout.trim();
  const services = serviceJson.startsWith('[') ? JSON.parse(serviceJson)
    : serviceJson.split('\n').filter(Boolean).map(line => JSON.parse(line));
  if (services.length !== 3 || services.some(s => s.Project !== project)) throw new Error('Unexpected Compose services');
  const apiId = (await compose(['ps', '-q', 'api'])).stdout.trim();
  const dbId = (await compose(['ps', '-q', 'db'])).stdout.trim();
  const migrateId = (await compose(['ps', '--all', '-q', 'migrate'])).stdout.trim();
  const inspection = JSON.parse((await command('docker', ['inspect', apiId, dbId, migrateId])).stdout);
  verifyMigrationIsolation(inspection[2], [inspection[0]]);
  verifySecretMounts(inspection[1], ['/run/secrets/admin_password', '/run/secrets/app_password', '/run/secrets/migrator_password']);
  if ([dbPassword, jwtSecret, testEnv.POSTMAN_TEST_ADMIN_PASSWORD, testEnv.POSTMAN_TEST_MIGRATOR_PASSWORD]
    .some(secret => JSON.stringify(inspection).includes(secret))) throw new Error('Secret value exposed in container metadata');
  summary.migrationChecks.push('Standalone migrator completed using the same image', 'API has no DDL credentials');
  console.log('Standalone migration completed; same image and runtime credential isolation verified.');
  if (inspection.some(c => c.Config.Labels['banking.test-suite'] !== 'postman' || c.Config.Labels['com.docker.compose.project'] !== project)) throw new Error('Isolation label verification failed');
  // Only provisioning code and this run's exact read-only secret files may be bound.
  if (inspection.some(c => c.Mounts.some(m => m.Type === 'volume' || (m.Type === 'bind' &&
    !(c.Id === dbId && m.Destination === '/docker-entrypoint-initdb.d' && !m.RW)
    && !isTestSecretBind(m, testEnv.POSTMAN_TEST_SECRETS_DIR))))) throw new Error('Persistent data mounts are forbidden');
  const port = (await compose(['port', 'api', '8080'])).stdout.trim();
  if (!/^127\.0\.0\.1:\d+$/.test(port)) throw new Error('Unexpected API binding');
  const baseUrl = 'http://' + port;
  if (args.has('--simulate-failure')) { stage = 'simulated-failure'; throw new Error('Intentional failure to verify cleanup'); }
  stage = 'database-privileges';
  const privilegeResult = await command('docker', ['exec', '-i', '-e', 'PGPASSWORD', dbId,
    'psql', '-X', '-h', 'db', '-U', 'postman_test', '-d', 'banking_postman_test', '-At', '-v', 'ON_ERROR_STOP=1'], {
    env: { ...testEnv, PGPASSWORD: dbPassword },
    input: `SELECT current_user = 'postman_test'
      AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
      AND NOT has_schema_privilege(current_user, 'public', 'CREATE')
      AND NOT has_database_privilege(current_user, current_database(), 'CREATE,TEMP')
      AND NOT has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT,INSERT,UPDATE,DELETE')
      AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member=pg_roles.oid)
      AND NOT EXISTS (SELECT FROM pg_class WHERE relnamespace='public'::regnamespace AND relowner=pg_roles.oid)
      AND has_table_privilege(current_user, 'public.accounts', 'SELECT,INSERT,UPDATE,DELETE')
      FROM pg_roles WHERE rolname=current_user;\n`
  });
  if (privilegeResult.stdout.trim() !== 't') throw new Error('Runtime database privileges are excessive');
  console.log('PostgreSQL runtime role: restricted privileges verified through authenticated SQL.');
  stage = 'fixtures';
  const adminEmail = `admin-${randomUUID()}@example.test`;
  const adminPassword = randomBytes(24).toString('base64url');
  const admin = await api(baseUrl, 'POST', '/customers', { fullName: 'Postman isolated administrator', cpf: String(randomBytes(5).readUIntBE(0, 5) % 90000000000 + 10000000000), email: adminEmail, password: adminPassword, phone: '81999999999', birthDate: '2000-01-01' });
  if (!/^[a-f0-9-]{36}$/.test(admin.id)) throw new Error('Invalid fixture ID');
  await command('docker', ['exec', '-i', dbId, 'psql', '-U', 'postman_admin', '-d', 'banking_postman_test', '-v', 'ON_ERROR_STOP=1'], {
    input: `UPDATE customers SET role = 'ADMIN' WHERE id = '${admin.id}'::uuid;\n`
  });
  const now = Math.floor(Date.now() / 1000);
  const encoded = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const unsigned = `${encoded({ alg: 'HS512', typ: 'JWT' })}.${encoded({ sub: admin.id, iat: now - 120, exp: now - 60 })}`;
  const expiredToken = unsigned + '.' + createHmac('sha512', jwtSecret).update(unsigned).digest('base64url');
  const runtimeEnv = { name: 'Disposable Postman runtime - do not import or share', values: Object.entries({ baseUrl, isolationId: project, adminEmail, adminPassword, expiredToken })
    .map(([key, value]) => ({ key, value, enabled: true, type: 'secret' })), _postman_variable_scope: 'environment' };
  const runtimeFile = path.join(privateDir, 'environment.json');
  await writeFile(runtimeFile, JSON.stringify(runtimeEnv), { mode: 0o600 });
  const rawFile = path.join(privateDir, 'native-report.json');
  stage = 'collection';
  console.log('Running complete endpoint collection...');
  const result = await command(cli.program, ['collection', 'run', collectionFile, '-e', runtimeFile,
    '--no-report-events', '--ignore-redirects', '--timeout-request', '15000', '--timeout-script', '10000',
    '-r', 'json', '--reporter-json-export', rawFile, '--reporter-json-structure', 'newman', '--reporter-json-omitAllHeadersAndBody'],
  { timeout: 300_000, allowFailure: true });
  const raw = JSON.parse(await readFile(rawFile, 'utf8'));
  summary = { ...summary, ...sanitizedReport(raw, collection) };
  if (result.code !== 0) summary.success = false;
  console.log(`${summary.requests} requests, ${summary.assertions} assertions, ${summary.failedAssertions} failed assertions.`);
  for (const failure of summary.executions.filter(e => !e.passed)) console.log(`FAIL: ${failure.name}; HTTP ${failure.status} (expected ${failure.expectedStatus}).`);
  exitCode = summary.success ? 0 : 1;
  if (args.has('--recovery') && summary.success) {
    stage = 'recovery';
    summary.recovery = await verifyRecovery({ command, compose, dbId, baseUrl, testEnv,
      onStage: name => { stage = 'recovery-' + name; console.log('Validating ' + stage + '...'); } });
    console.log(`${summary.recovery.checks.length} recovery checks passed; restored data and privileges verified.`);
  }
  if (summary.success) {
    stage = 'secret-rotation';
    summary.secretChecks = await verifySecretRotation({ command, compose, dbId, directory: testEnv.POSTMAN_TEST_SECRETS_DIR,
      email: adminEmail, password: adminPassword, customerId: admin.id, oldDbPassword: dbPassword, testEnv,
      onStage: name => { stage = 'secret-rotation-' + name; console.log('Validating ' + stage + '...'); } });
    console.log('JWT and PostgreSQL rotation verified in isolated database.');
  }
  if (args.has('--interactive') && summary.success) {
    await saveReports();
    const interactiveUrl = await discoverHealthyApi(compose);
    console.log(`Inspection window: ${interactiveUrl}; isolationId=${project}. Ctrl+C ends it; automatic cleanup after 10 minutes.`);
    console.log('Import only the blank environment template into the extension. CLI reports are in target/postman/latest.');
    await new Promise(resolve => { const timer = setTimeout(resolve, 600_000); releaseInteractive = () => { clearTimeout(timer); resolve(); }; });
  }
} catch (error) {
  summary.success = false;
  if (!interrupted && error?.category === 'application-verification') summary.applicationFailure = stage;
  else summary.infrastructureFailure = interrupted ? 'interrupted' : stage;
  console.error(`Validation failed during ${stage} (${summary.applicationFailure ? 'application verification' : 'infrastructure'}). No sensitive native output was saved.`);
  exitCode = 1;
} finally {
  cleaning = true;
  if (interrupted) { summary.success = false; summary.infrastructureFailure = 'interrupted'; exitCode = 1; }
  console.log('Removing this run\'s temporary Docker resources...');
  try {
    // Explicit file and random project name prevent touching the development stack.
    await compose(['down', '--volumes', '--remove-orphans'], { timeout: 120_000 });
    if (!borrowedImage) {
      const owned = await command('docker', ['image', 'ls', '-q', `${project}-api`]);
      if (owned.stdout.trim()) await command('docker', ['image', 'rm', `${project}-api`]);
    }
    const remaining = await command('docker', ['ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`]);
    const networks = await command('docker', ['network', 'ls', '-q', '--filter', `label=com.docker.compose.project=${project}`]);
    const volumes = await command('docker', ['volume', 'ls', '-q', '--filter', `label=com.docker.compose.project=${project}`]);
    summary.cleanup = !remaining.stdout.trim() && !networks.stdout.trim() && !volumes.stdout.trim();
  } catch { summary.cleanup = false; }
  if (!summary.cleanup) { summary.success = false; exitCode = 1; console.error(`Cleanup could not be confirmed for ${project}.`); }
  // Only remove the exact private directory allocated by mkdtemp above.
  if (path.dirname(path.resolve(privateDir)) !== path.resolve(tmpdir()) || !path.basename(privateDir).startsWith('banking-postman-private-')) throw new Error('Unsafe temporary path');
  try { await rm(privateDir, { recursive: true, force: true }); }
  catch { summary.success = false; summary.infrastructureFailure = 'private-file-cleanup'; summary.cleanup = false; exitCode = 1; }
  await saveReports();
  console.log(`Result: ${summary.success ? 'PASS' : 'FAIL'}. Cleanup: ${summary.cleanup ? 'confirmed' : 'unconfirmed'}.`);
  console.log('Reports: target/postman/latest/summary.md and report.html');
  process.exitCode = exitCode;
}
