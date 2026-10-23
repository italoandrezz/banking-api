import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertRestoreTarget, assertRecoveryIsolation, discoverHealthyApi } from './recovery.mjs';
import { junitReport } from './report.mjs';

test('Recovery checks are separate JUnit cases without inflating Postman assertions', () => {
  const summary = { success: true, requests: 0, assertions: 0, executions: [], cleanup: true,
    recovery: { checks: ['Restart', 'Restore'] } };
  assert.match(junitReport(summary), /tests="2" failures="0" errors="0"/);
  assert.equal(summary.assertions, 0);
});
test('Recovery application verification and infrastructure failures remain distinguishable', () => {
  const base = { success: false, requests: 0, executions: [], cleanup: true };
  assert.match(junitReport({ ...base, applicationFailure: 'recovery-commit' }), /Recovery application verification/);
  assert.match(junitReport({ ...base, infrastructureFailure: 'recovery-restart' }), /Infrastructure or interruption/);
  assert.match(junitReport({ ...base, applicationFailure: 'recovery-commit' }), /errors="1"/);
});

test('Restart rediscovers changed ports and waits for health on the current endpoint', async () => {
  let calls = 0; const visited = [];
  const compose = async args => { assert.deepEqual(args, ['port', 'api', '8080']); return { stdout: `127.0.0.1:${++calls === 1 ? 12001 : 12002}\n` }; };
  const result = await discoverHealthyApi(compose, { attempts: 3, sleep: async () => {}, fetcher: async url => {
    visited.push(url); if (visited.length === 1) throw new Error('Connection refused');
    return { ok: true, json: async () => ({ status: 'UP' }) };
  } });
  assert.equal(result, 'http://127.0.0.1:12002');
  assert.deepEqual(visited, ['http://127.0.0.1:12001/actuator/health', 'http://127.0.0.1:12002/actuator/health']);
});
test('Persistent unavailability fails instead of returning a stale endpoint', async () => {
  await assert.rejects(discoverHealthyApi(async () => ({ stdout: '127.0.0.1:12001' }), {
    attempts: 2, sleep: async () => {}, fetcher: async () => ({ ok: false })
  }), /health deadline/);
});
test('Port discovery rejects unsafe bindings and propagates Compose failures', async () => {
  for (const stdout of ['', '0.0.0.0:12001', '127.0.0.1:0', '127.0.0.1:65536', '127.0.0.1:1\n127.0.0.1:2']) {
    await assert.rejects(discoverHealthyApi(async () => ({ stdout })), /port discovery/);
  }
  await assert.rejects(discoverHealthyApi(async () => { throw new Error('Compose unavailable'); }), /Compose unavailable/);
});

const cluster = () => ({ Config: { Labels: { 'banking.test-suite': 'postman',
  'com.docker.compose.project': 'banking-postman-0123456789abcdef' } },
  HostConfig: { Tmpfs: { '/var/lib/postgresql/data': '' } }, Mounts: [] });
test('Isolation supports Docker HostConfig tmpfs and explicit tmpfs mounts', () => {
  assert.doesNotThrow(() => assertRecoveryIsolation(cluster(), 'http://127.0.0.1:12345'));
  const alternate = cluster(); alternate.HostConfig = {};
  alternate.Mounts = [{ Type: 'tmpfs', Destination: '/var/lib/postgresql/data' }];
  assert.doesNotThrow(() => assertRecoveryIsolation(alternate, 'http://127.0.0.1:12345'));
});
test('Isolation refuses persistent data, missing tmpfs, foreign projects and external targets', () => {
  const persistent = cluster(); persistent.Mounts = [{ Type: 'volume', Destination: '/var/lib/postgresql/data' }];
  const disk = cluster(); disk.HostConfig = {};
  const foreign = cluster(); foreign.Config.Labels['com.docker.compose.project'] = 'banking-dev';
  for (const value of [persistent, disk, foreign]) assert.throws(() => assertRecoveryIsolation(value, 'http://127.0.0.1:12345'));
  assert.throws(() => assertRecoveryIsolation(cluster(), 'https://bank.example'));
});

test('Restore target accepts only a fresh disposable database', () => {
  assert.doesNotThrow(() => assertRestoreTarget('banking_restore_0123456789abcdef', ['banking_postman_test']));
});
test('Restore refuses an existing target even with a valid disposable name', () => {
  const name = 'banking_restore_0123456789abcdef';
  assert.throws(() => assertRestoreTarget(name, [name]));
});
test('Restore rejects development databases, paths and SQL injection', () => {
  for (const name of ['banking_db', 'banking_postman_test', 'postgres', '', '../banking_restore_0123456789abcdef', 'banking_restore_0123456789abcdef; DROP DATABASE postgres']) {
    assert.throws(() => assertRestoreTarget(name, []));
  }
});
