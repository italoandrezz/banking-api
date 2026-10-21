// Targeted real HTTP tests. Always creates its own tmpfs database and two API replicas.
import { spawn } from 'node:child_process';
import { mkdtemp, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { verifyMigrationIsolation } from '../postman/migration-isolation.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const project = 'banking-rate-' + randomBytes(8).toString('hex');
const privateDir = await mkdtemp(path.join(tmpdir(), 'banking-rate-'));
const file = path.join(privateDir, 'compose.json');
const empty = path.join(privateDir, 'empty.env');
const output = path.join(root, 'target/rate-limiting');
const env = { ...process.env, DB_NAME: 'banking_rate_test', DB_ADMIN_USERNAME: 'rate_admin', DB_USERNAME: 'rate_app',
  FLYWAY_USERNAME: 'rate_migrator', API_PORT: '0', DB_ADMIN_PASSWORD: randomBytes(32).toString('hex'),
  DB_PASSWORD: randomBytes(32).toString('hex'), FLYWAY_PASSWORD: randomBytes(32).toString('hex'), JWT_SECRET: randomBytes(32).toString('hex') };
// Default profile is tested regardless of the caller's local overrides.
for (const key of Object.keys(env)) if (key.startsWith('AUTH_') || /^COMPOSE_/.test(key)) delete env[key];
const children = new Set();
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  interrupted = true;
  for (const child of children) child.kill();
});
async function command(executable, args, { input, allowFailure = false, timeout = 300_000 } = {}) {
  return new Promise((resolve, reject) => {
    const proc = spawn(executable, args, { cwd: root, env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    children.add(proc);
    let stdout = '', stderr = '';
    proc.stdout.on('data', b => { stdout += b; }); proc.stderr.on('data', b => { stderr += b; });
    const timer = setTimeout(() => proc.kill(), timeout);
    proc.on('error', () => { clearTimeout(timer); children.delete(proc); reject(new Error('Test infrastructure unavailable')); });
    proc.on('close', code => {
      clearTimeout(timer); children.delete(proc);
      if (code === 0 || allowFailure) resolve({ code, stdout, stderr });
      else reject(new Error('Test infrastructure command failed'));
    });
    proc.stdin.on('error', () => {}); proc.stdin.end(input);
  });
}
const docker = (args, options) => command('docker', args, options);
const compose = (args, options) => docker(['compose', '--env-file', empty, '-p', project, '-f', file, ...args], options);
const checks = [];
let stage = 'configuration', requests = 0, cleanup = false, success = false, config;
const clients = [project + '-client-a', project + '-client-b'];
const image = project + '-api';
const check = async (name, fn) => {
  stage = name;
  if (interrupted) throw new Error('Interrupted');
  await fn(); checks.push(name); console.log('PASS: ' + name);
};
async function request(client, route, { method = 'POST', body = '{}', headers = [] } = {}) {
  const args = ['exec', '-i', client, 'curl', '--silent', '--show-error', '--max-time', '12', '--path-as-is',
    '--dump-header', '-', '-X', method, '-H', 'Content-Type: application/json'];
  for (const header of headers) args.push('-H', header);
  if (method === 'POST') args.push('--data-binary', '@-');
  args.push('http://nginx:8080' + route);
  requests++;
  const result = await docker(args, { input: method === 'POST' ? body : undefined });
  const split = result.stdout.indexOf('\r\n\r\n');
  assert.ok(split > 0, 'Missing HTTP envelope');
  const lines = result.stdout.slice(0, split).split('\r\n');
  return { status: Number(lines[0].split(' ')[1]), body: result.stdout.slice(split + 4),
    headers: Object.fromEntries(lines.slice(1).map(line => {
      const i = line.indexOf(':'); return [line.slice(0, i).toLowerCase(), line.slice(i + 1).trim()];
    })) };
}
async function profile(values) {
  config.services.nginx.environment = { ...config.services.nginx.environment, ...values };
  await writeFile(file, JSON.stringify(config), { mode: 0o600 });
  await compose(['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '60', 'nginx']);
}

try {
  await writeFile(empty, '');
  const raw = await docker(['compose', '--env-file', empty, '-p', project, '-f', 'compose.yaml', 'config', '--format', 'json']);
  config = JSON.parse(raw.stdout);
  assert.deepEqual(Object.keys(config.services).sort(), ['api', 'db', 'migrate', 'nginx']);
  assert.ok(!config.services.api.ports && !config.services.db.ports);
  assert.equal(config.services.db.volumes.filter(v => v.target === '/var/lib/postgresql/data').length, 1);
  config.services.db.volumes = config.services.db.volumes.filter(v => v.target !== '/var/lib/postgresql/data');
  assert.ok(config.services.db.volumes.every(v => v.type === 'bind' && v.read_only));
  config.services.db.tmpfs = ['/var/lib/postgresql/data'];
  config.volumes = {};
  config.services.api.image = image;
  config.services.migrate.image = image;
  config.services.nginx.image = project + '-nginx';
  for (const service of Object.values(config.services)) {
    service.restart = 'no'; service.labels = { ...service.labels, 'banking.test-suite': 'rate-limiting' };
  }
  await writeFile(file, JSON.stringify(config), { mode: 0o600 });
  console.log('Isolated project: ' + project);
  stage = 'build'; await compose(['build', 'api', 'nginx'], { timeout: 900_000 });
  stage = 'startup'; await compose(['up', '-d', '--no-build', '--scale', 'api=2', '--wait', '--wait-timeout', '180']);
  const binding = (await compose(['port', 'nginx', '8080'])).stdout.trim();
  assert.match(binding, /^127\.0\.0\.1:\d+$/);
  const apiIds = (await compose(['ps', '-q', 'api'])).stdout.trim().split(/\s+/);
  assert.equal(apiIds.length, 2);
  const migrateId = (await compose(['ps', '--all', '-q', 'migrate'])).stdout.trim();
  const inspected = JSON.parse((await docker(['inspect', migrateId, ...apiIds])).stdout);
  verifyMigrationIsolation(inspected[0], inspected.slice(1));
  for (const client of clients) await docker(['run', '-d', '--name', client, '--label', 'banking.test-suite=rate-limiting',
    '--network', project + '_edge', '--entrypoint', 'sleep', image, '900']);

  await check('default-limits-preserve-existing-15-request-smoke', async () => {
    await command(process.platform === 'win32' ? 'powershell.exe' : 'pwsh',
      ['-NoProfile', '-File', 'scripts/Test-Compose.ps1', '-BaseUrl', 'http://' + binding]);
  });
  await check('two-replicas-serve-correlated-health-without-direct-access', async () => {
    const ids = new Set();
    for (let i = 0; i < 12; i++) {
      const id = randomUUID(); ids.add(id);
      const res = await request(clients[0], '/actuator/health', { method: 'GET', headers: ['X-Correlation-ID: ' + id] });
      assert.equal(res.status, 200); assert.equal(res.headers['x-correlation-id'], id);
    }
    for (const id of apiIds) {
      const info = JSON.parse((await docker(['inspect', id])).stdout)[0];
      assert.ok(!Object.values(info.HostConfig.PortBindings || {}).some(v => v?.length));
      assert.ok(!info.NetworkSettings.Networks[project + '_edge']);
      const ip = info.NetworkSettings.Networks[project + '_backend'].IPAddress;
      assert.match(ip, /^\d+\.\d+\.\d+\.\d+$/);
      const direct = await docker(['exec', clients[0], 'curl', '--silent', '--max-time', '2', 'http://' + ip + ':8080/actuator/health'], { allowFailure: true });
      assert.notEqual(direct.code, 0);
      const logs = await docker(['logs', id]);
      assert.ok([...ids].some(value => logs.stdout.includes(value)), 'Both API replicas must actually receive requests');
    }
  });
  await profile({ AUTH_LOGIN_RATE: '1', AUTH_LOGIN_BURST: '2', AUTH_REGISTER_RATE: '1', AUTH_REGISTER_BURST: '1',
    AUTH_TOTAL_RATE: '60000', AUTH_TOTAL_BURST: '100' });
  await check('per-ip-burst-and-json-429', async () => {
    for (let i = 0; i < 3; i++) assert.equal((await request(clients[0], '/auth/login')).status, 400);
    const id = randomUUID();
    const res = await request(clients[0], '/auth/login', { headers: ['X-Correlation-ID: ' + id] });
    assert.equal(res.status, 429); assert.equal(res.headers['retry-after'], '60');
    assert.equal(res.headers['content-type'], 'application/json'); assert.equal(res.headers['cache-control'], 'no-store');
    assert.equal(res.headers['x-correlation-id'], id);
    assert.deepEqual(JSON.parse(res.body), { status: 429, error: 'Too Many Requests', message: 'Request limit exceeded. Try again later.' });
  });
  await check('forged-headers-and-query-cannot-reset-quota', async () => {
    for (const headers of [ ['X-Forwarded-For: 198.51.100.1'], ['X-Real-IP: 198.51.100.2'],
      ['Forwarded: for=198.51.100.3'], ['X-Forwarded-For: 198.51.100.4', 'X-Forwarded-For: 198.51.100.5'],
      ['X-Forwarded-For: 198.51.100.6, 198.51.100.7', 'Host: alternate.example.test'] ]) {
      assert.equal((await request(clients[0], '/auth/login?nonce=' + randomUUID(), { headers })).status, 429);
    }
  });
  await check('ip-and-registration-quotas-are-independent', async () => {
    assert.equal((await request(clients[1], '/auth/login')).status, 400);
    for (let i = 0; i < 2; i++) assert.equal((await request(clients[0], '/customers')).status, 400);
    assert.equal((await request(clients[0], '/customers')).status, 429);
    assert.equal((await request(clients[1], '/customers')).status, 400);
  });
  await check('url-variants-do-not-bypass-limits', async () => {
    for (const route of ['//auth/login', '/auth//login', '/auth/./login', '/unused/../auth/login', '/auth/login/'])
      assert.equal((await request(clients[0], route)).status, 429);
    for (const route of ['/auth/%6cogin', '/%61uth/login', '/auth%2flogin', '/auth%252flogin', '/auth/login;x=1', '/auth;x=1/login', '/auth\\login'])
      assert.equal((await request(clients[0], route)).status, 400);
    for (const route of ['//customers', '/unused/../customers', '/customers/'])
      assert.equal((await request(clients[0], route)).status, 429);
    for (const route of ['/customers;x=1', '/%63ustomers']) assert.equal((await request(clients[0], route)).status, 400);
    assert.notEqual((await request(clients[0], '/AUTH/LOGIN')).status, 200);
  });
  await check('health-jwt-and-other-methods-unaffected-by-exhaustion', async () => {
    for (const route of ['/actuator/health/liveness', '/actuator/health/readiness'])
      assert.equal((await request(clients[0], route, { method: 'GET' })).status, 200);
    assert.equal((await request(clients[0], '/accounts', { method: 'GET' })).status, 401);
    assert.equal((await request(clients[0], '/actuator/env', { method: 'GET' })).status, 401);
    assert.equal((await request(clients[0], '/auth/login', { method: 'GET' })).status, 401);
  });
  await profile({ AUTH_LOGIN_RATE: '1', AUTH_LOGIN_BURST: '2' });
  await check('concurrent-requests-share-quota-across-workers-and-replicas', async () => {
    const responses = await Promise.all(Array.from({ length: 12 }, () => request(clients[0], '/auth/login')));
    assert.equal(responses.filter(r => r.status === 400).length, 3);
    assert.equal(responses.filter(r => r.status === 429).length, 9);
  });
  await profile({ AUTH_LOGIN_RATE: '60000', AUTH_LOGIN_BURST: '100', AUTH_REGISTER_RATE: '60000', AUTH_REGISTER_BURST: '100',
    AUTH_TOTAL_RATE: '1', AUTH_TOTAL_BURST: '2' });
  await check('aggregate-quota-shared-by-both-ips-and-endpoints', async () => {
    assert.equal((await request(clients[0], '/auth/login')).status, 400);
    assert.equal((await request(clients[1], '/customers')).status, 400);
    assert.equal((await request(clients[0], '/customers')).status, 400);
    const response = await request(clients[1], '/auth/login', { headers: ['Host: changed.example.test'] });
    assert.equal(response.status, 429); assert.equal(response.headers['retry-after'], '60');
  });
  await profile({ AUTH_LOGIN_RATE: '60', AUTH_LOGIN_BURST: '1', AUTH_TOTAL_RATE: '60000', AUTH_TOTAL_BURST: '100' });
  await check('quota-refills-and-invalid-correlation-id-is-replaced', async () => {
    for (let i = 0; i < 2; i++) assert.equal((await request(clients[0], '/auth/login')).status, 400);
    const res = await request(clients[0], '/auth/login', { headers: ['X-Correlation-ID: invalid-private-value'] });
    assert.equal(res.status, 429); assert.equal(res.headers['retry-after'], '1');
    assert.match(res.headers['x-correlation-id'], /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
    await new Promise(resolve => setTimeout(resolve, 1200));
    assert.equal((await request(clients[0], '/auth/login')).status, 400);
  });
  await check('proxy-logs-contain-only-safe-metadata', async () => {
    await request(clients[0], '/actuator/health?private-query@example.test', { method: 'GET',
      headers: ['X-Correlation-ID: private-correlation', 'Authorization: Bearer private-jwt', 'Cookie: password=private-password', 'X-Forwarded-For: 198.51.100.123'] });
    await request(clients[0], '/customers', { body: '{"password":"private-password","cpf":"12345678901","email":"private-body@example.test","balance":1234.56}' });
    const nginx = (await compose(['ps', '-q', 'nginx'])).stdout.trim();
    const logs = await docker(['logs', nginx]);
    const text = logs.stdout + logs.stderr;
    for (const forbidden of ['private-query', 'private-correlation', 'private-jwt', 'private-password', 'private-body', '12345678901', '1234.56', '198.51.100.123'])
      assert.ok(!text.includes(forbidden), 'Proxy log privacy regression');
    const lines = text.trim().split(/\r?\n/).filter(Boolean);
    assert.ok(lines.length > 0);
    for (const line of lines) assert.deepEqual(Object.keys(JSON.parse(line)).sort(),
      ['timestamp', 'event', 'status', 'durationSeconds', 'limiter', 'correlationId'].sort());
  });
  await check('invalid-limit-configuration-fails-closed', async () => {
    for (const value of ['0', '-1', '1; injected', '99999999999999']) {
      const result = await compose(['run', '--rm', '--no-deps', '-e', 'AUTH_LOGIN_RATE=' + value, 'nginx'], { allowFailure: true });
      assert.notEqual(result.code, 0); assert.ok((result.stdout + result.stderr).includes('proxy_configuration_invalid'));
    }
  });
  success = !interrupted;
} catch {
  console.error('FAIL during ' + stage + '; native output withheld to protect test credentials.');
} finally {
  try {
    for (const client of clients) await docker(['rm', '-f', client], { allowFailure: true });
    await compose(['down', '--volumes', '--remove-orphans']);
    for (const resource of [['ps', '-aq'], ['network', 'ls', '-q'], ['volume', 'ls', '-q']]) {
      const left = await docker([...resource, '--filter', 'label=com.docker.compose.project=' + project]);
      assert.equal(left.stdout.trim(), '');
    }
    for (const client of clients) assert.equal((await docker(['ps', '-aq', '--filter', 'name=^/' + client + '$'])).stdout.trim(), '');
    await docker(['image', 'rm', image], { allowFailure: true });
    await docker(['image', 'rm', project + '-nginx'], { allowFailure: true });
    assert.equal(path.dirname(privateDir), path.resolve(tmpdir()));
    assert.ok(path.basename(privateDir).startsWith('banking-rate-'));
    await rm(privateDir, { recursive: true, force: true });
    cleanup = true;
  } catch { console.error('Temporary resource cleanup could not be confirmed. Project: ' + project); }
  success &&= cleanup && !interrupted;
  await mkdir(output, { recursive: true });
  const summary = { success, cleanup, checks, requests, failedStage: success ? null : stage };
  await writeFile(path.join(output, 'summary.json'), JSON.stringify(summary, null, 2));
  await writeFile(path.join(output, 'summary.md'), `# Rate limiting\n\n${success ? 'PASS' : 'FAIL'}; cleanup: ${cleanup}; ${checks.length} checks; ${requests} targeted HTTP requests plus 15 smoke requests.\n\n${checks.map(c => '- PASS: ' + c).join('\n')}\n${success ? '' : '\nFailed stage: ' + stage}\n`);
  console.log(`Result: ${success ? 'PASS' : 'FAIL'}; cleanup: ${cleanup}; checks: ${checks.length}; reports: target/rate-limiting`);
  process.exitCode = success ? 0 : 1;
}
