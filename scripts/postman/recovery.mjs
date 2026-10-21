import { randomBytes, randomUUID } from 'node:crypto';

export class RecoveryAssertionError extends Error {
  constructor() { super('Recovery application verification failed'); this.category = 'application-verification'; }
}

export async function discoverHealthyApi(compose, { fetcher = fetch, sleep = ms => new Promise(r => setTimeout(r, ms)), attempts = 90 } = {}) {
  let discovered = false;
  for (let attempt = 0; attempt < attempts; attempt++) {
    const port = (await compose(['port', 'api', '8080'])).stdout.trim();
    if (!/^127\.0\.0\.1:\d+$/.test(port) || Number(port.split(':')[1]) > 65535 || Number(port.split(':')[1]) < 1) {
      throw new Error('Recovery port discovery failed');
    }
    const url = 'http://' + port;
    discovered = true;
    try {
      const response = await fetcher(url + '/actuator/health', { redirect: 'error', signal: AbortSignal.timeout(2000) });
      if (response.ok && (await response.json()).status === 'UP') return url;
      await response.body?.cancel();
    } catch { /* Startup and database recovery may temporarily refuse connections. */ }
    if (attempt + 1 < attempts) await sleep(1000);
  }
  throw new Error(discovered ? 'Recovery health deadline exceeded' : 'Recovery port discovery failed');
}

export function assertRestoreTarget(name, existing) {
  if (!/^banking_restore_[a-f0-9]{16}$/.test(name) || existing.includes(name)) {
    throw new Error('Restore requires a new disposable database');
  }
}

// Called only inside the disposable Postman stack. No external targets are accepted.
export function assertRecoveryIsolation(inspected, baseUrl) {
  const dataPath = '/var/lib/postgresql/data';
  const mounts = inspected.Mounts || [];
  const tmpfs = Object.hasOwn(inspected.HostConfig?.Tmpfs || {}, dataPath)
    || mounts.some(m => m.Type === 'tmpfs' && m.Destination === dataPath);
  if (inspected.Config?.Labels?.['banking.test-suite'] !== 'postman'
      || !/^banking-postman-[a-f0-9]{16}$/.test(inspected.Config?.Labels?.['com.docker.compose.project'])
      || !tmpfs || mounts.some(m => m.Destination === dataPath && m.Type !== 'tmpfs')
      || !/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)) throw new Error('Recovery requires an isolated tmpfs cluster');
}

export async function verifyRecovery({ command, compose, dbId, baseUrl, testEnv, onStage = () => {} }) {
  onStage('isolation');
  const inspected = JSON.parse((await command('docker', ['inspect', dbId])).stdout)[0];
  assertRecoveryIsolation(inspected, baseUrl);
  const database = 'banking_postman_test';
  const restored = 'banking_restore_' + randomBytes(8).toString('hex');
  const backup = '/tmp/banking-recovery-' + randomBytes(16).toString('hex') + '.dump';
  const checks = [];
  const require = condition => { if (!condition) throw new RecoveryAssertionError(); };
  const sql = async (query, db = database, runtime = false, allowFailure = false) => {
    const result = await command('docker', ['exec', '-i', '-e', 'PGPASSWORD', dbId, 'psql', '-X', '-h', '127.0.0.1',
      '-U', runtime ? 'postman_test' : 'postman_admin', '-d', db, '-At', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose'], {
      input: query, allowFailure,
      env: { ...testEnv, PGPASSWORD: runtime ? testEnv.POSTMAN_TEST_DB_PASSWORD : testEnv.POSTMAN_TEST_ADMIN_PASSWORD }
    });
    return result;
  };
  let token;
  const request = async (method, route, body, key, discard = false) => {
    const response = await fetch(baseUrl + route, { method, redirect: 'error', signal: AbortSignal.timeout(15_000),
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body) });
    require(response.ok, 'Recovery HTTP request failed');
    // Simulate a client that loses/discards the response after the server committed.
    if (discard) { await response.body?.cancel(); return; }
    return response.json();
  };
  const waitHealthy = async () => { baseUrl = await discoverHealthyApi(compose); };
  onStage('fixtures');
  const email = 'recovery-' + randomUUID() + '@example.test';
  const password = randomBytes(24).toString('base64url');
  const customer = await request('POST', '/customers', { fullName: 'Isolated recovery fixture', email, password,
    cpf: String(randomBytes(5).readUIntBE(0, 5) % 90000000000 + 10000000000), birthDate: '2000-01-01', phone: '81999999999' });
  token = (await request('POST', '/auth/login', { email, password })).token;
  const account = await request('POST', '/accounts', { customerId: customer.id });
  require(/^[a-f0-9-]{36}$/.test(account.id) && /^[a-f0-9-]{36}$/.test(customer.id), 'Invalid fixture IDs');
  const key = randomUUID();
  onStage('commit');
  await request('POST', `/accounts/${account.id}/deposit`, { amount: 100 }, key, true);
  const financialState = async () => (await sql(`SELECT balance::text || ':' ||
    (SELECT count(*) FROM transactions WHERE account_id='${account.id}') || ':' ||
    (SELECT count(*) FROM financial_idempotency WHERE customer_id='${customer.id}' AND idempotency_key='${key}')
    FROM accounts WHERE id='${account.id}';`)).stdout.trim();
  require(await financialState() === '100.00:1:1', 'Committed operation must exist exactly once');
  checks.push('Commit confirmado sem consumir o corpo da resposta');
  onStage('restart');
  await compose(['restart', 'api']);
  await waitHealthy();
  const replay = await request('POST', `/accounts/${account.id}/deposit`, { amount: 100 }, key);
  require(replay.balance === 100 && await financialState() === '100.00:1:1', 'Restart duplicated the operation');
  checks.push('Replay idempotente apos reinicializacao da API');
  onStage('database-outage');
  await command('docker', ['pause', dbId]);
  let outageObserved = false;
  try {
    const live = await fetch(baseUrl + '/actuator/health/liveness', { signal: AbortSignal.timeout(5000) });
    require(live.status === 200 && (await live.json()).status === 'UP');
    const ready = await fetch(baseUrl + '/actuator/health/readiness', { signal: AbortSignal.timeout(15000) });
    const body = await ready.json();
    outageObserved = ready.status === 503 && body.status === 'DOWN';
    require(!body.components && !body.details);
  } finally { await command('docker', ['unpause', dbId]); }
  require(outageObserved, 'Database outage was not observed');
  await waitHealthy();
  const readyAgain = await fetch(baseUrl + '/actuator/health/readiness', { signal: AbortSignal.timeout(15000) });
  require(readyAgain.status === 200 && (await readyAgain.json()).status === 'UP');
  checks.push('Liveness UP e readiness 503 durante falha real do banco; readiness UP apos recuperacao');
  await request('POST', `/accounts/${account.id}/deposit`, { amount: 100 }, key);
  require(await financialState() === '100.00:1:1', 'Database recovery changed committed state');
  checks.push('Recuperacao da conexao apos indisponibilidade do PostgreSQL');

  const tables = ['customers', 'addresses', 'accounts', 'transactions', 'financial_idempotency', 'flyway_schema_history'];
  const snapshot = async db => {
    const rows = [];
    for (const table of tables) {
      const result = await sql(`SELECT count(*) || ':' || md5(coalesce(string_agg(row_to_json(t)::text, '' ORDER BY row_to_json(t)::text), '')) FROM public.${table} t;`, db);
      rows.push(result.stdout.trim());
    }
    return rows;
  };
  onStage('snapshot');
  const before = await snapshot(database);
  try {
    onStage('backup');
    await command('docker', ['exec', dbId, 'pg_dump', '-U', 'postman_admin', '-d', database, '--format=custom', '--file=' + backup]);
    const existing = (await sql('SELECT datname FROM pg_database;')).stdout.trim().split('\n');
    assertRestoreTarget(restored, existing);
    // CREATE without IF NOT EXISTS is also an atomic refusal of existing targets.
    await sql(`CREATE DATABASE ${restored} TEMPLATE template0;`);
    onStage('restore');
    await command('docker', ['exec', dbId, 'pg_restore', '-U', 'postman_admin', '-d', restored, '--exit-on-error', backup]);
    await sql(`REVOKE ALL ON DATABASE ${restored} FROM PUBLIC;
      GRANT CONNECT ON DATABASE ${restored} TO postman_test, postman_migrator;
      ALTER ROLE postman_migrator IN DATABASE ${restored} SET banking.application_role TO 'postman_test';`);
    onStage('comparison');
    const after = await snapshot(restored);
    require(JSON.stringify(before) === JSON.stringify(after), 'Restored financial data or Flyway history differs');
    checks.push('Backup consistente e restauracao integral em banco novo');
    onStage('permissions');
    const permissions = await sql(`SELECT NOT has_schema_privilege(current_user,'public','CREATE')
      AND NOT has_database_privilege(current_user,current_database(),'CREATE,TEMP')
      AND NOT has_table_privilege(current_user,'flyway_schema_history','SELECT,INSERT,UPDATE,DELETE')
      AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
      FROM pg_roles WHERE rolname=current_user;`, restored, true);
    require(permissions.stdout.trim() === 't', 'Restored permissions are excessive');
    const denied = await sql('CREATE TABLE public.forbidden_recovery(id int);', restored, true, true);
    require(denied.code !== 0 && denied.stderr.includes('42501'), 'Restored runtime role must reject DDL');
    await sql(`BEGIN; SELECT * FROM accounts FOR UPDATE; UPDATE accounts SET balance=balance; ROLLBACK;`, restored, true);
    require(JSON.stringify(after) === JSON.stringify(await snapshot(restored)), 'Runtime verification changed restored data');
    checks.push('Permissoes e locks preservados apos restauracao');
    onStage('restored-api');
    const restoredApi = inspected.Config.Labels['com.docker.compose.project'] + '-restored-api';
    try {
      // A second instance reads only the new restored database. No migrations run.
      await compose(['run', '--detach', '--no-deps', '--name', restoredApi, '--publish', '127.0.0.1::8080',
        '-e', `DATABASE_URL=jdbc:postgresql://db:5432/${restored}`, '-e', 'SPRING_FLYWAY_ENABLED=false',
        'api']);
      baseUrl = await discoverHealthyApi(async () => command('docker', ['port', restoredApi, '8080/tcp']));
      token = (await request('POST', '/auth/login', { email, password })).token;
      const restoredAccount = await request('GET', `/accounts/${account.id}`);
      require(restoredAccount.balance === 100);
      const restoredReplay = await request('POST', `/accounts/${account.id}/deposit`, { amount: 100 }, key);
      require(restoredReplay.balance === 100 && JSON.stringify(after) === JSON.stringify(await snapshot(restored)));
      checks.push('Login, saldo e replay HTTP no banco restaurado sem novas migrations');
    } finally { await command('docker', ['rm', '-f', restoredApi]); }
    return { checks, rows: Object.fromEntries(tables.map((table, index) => [table, Number(before[index].split(':')[0])])) };
  } finally {
    // The exact randomly generated file belongs to this disposable container only.
    await command('docker', ['exec', dbId, 'rm', '-f', '--', backup]);
  }
}
