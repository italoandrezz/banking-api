import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { writeTestSecrets } from './secret-files.mjs';
import { verifySecretRotation } from './secret-rotation.mjs';

test('rotation accepts explicit tmpfs mounts and verifies old password via service SCRAM address', async t => {
  const parent = await mkdtemp(path.join(tmpdir(), 'banking-rotation-unit-'));
  try {
    const directory = path.join(parent, 'files');
    const values = Object.fromEntries(['admin_password', 'app_password', 'migrator_password', 'jwt_secret'].map(n => [n, randomBytes(32).toString('hex')]));
    await writeTestSecrets(directory, values);
    let logins = 0, recreated = false, rejected = false;
    t.mock.method(globalThis, 'fetch', async (url, options) => {
      if (url.endsWith('/actuator/health')) return { ok: true, json: async () => ({ status: 'UP' }) };
      if (url.endsWith('/auth/login')) return { status: 200, json: async () => ({ token: ++logins === 1 ? 'old-test-token' : 'new-test-token' }) };
      return { status: options.headers.Authorization === 'Bearer old-test-token' ? 401 : 200 };
    });
    const compose = async args => {
      if (args[0] === 'port') return { stdout: '127.0.0.1:12345' };
      assert.ok(args.includes('--force-recreate')); assert.ok(args.includes('--no-deps')); recreated = true;
    };
    const command = async (program, args) => {
      if (args[0] === 'inspect') return { stdout: JSON.stringify([{ Config: { Labels: { 'banking.test-suite': 'postman',
        'com.docker.compose.project': 'banking-postman-0123456789abcdef' } }, HostConfig: {},
        Mounts: [{ Type: 'tmpfs', Destination: '/var/lib/postgresql/data' }] }]) };
      if (args.includes('PGPASSWORD')) {
        assert.equal(args[args.indexOf('-h') + 1], 'db'); rejected = true;
        return { code: 2, stderr: 'password authentication failed' };
      }
      return { code: 0 };
    };
    const checks = await verifySecretRotation({ command, compose, dbId: 'disposable', directory, email: 'fixture@example.test',
      password: 'synthetic', customerId: 'synthetic', oldDbPassword: values.app_password, testEnv: {} });
    assert.equal(checks.length, 2); assert.ok(recreated && rejected);
    assert.notEqual(await readFile(path.join(directory, 'jwt_secret'), 'utf8'), values.jwt_secret);
    assert.notEqual(await readFile(path.join(directory, 'app_password'), 'utf8'), values.app_password);
  } finally { await rm(parent, { recursive: true, force: true }); }
});
