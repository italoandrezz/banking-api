import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { writeTestSecrets, verifySecretMounts, isTestSecretBind } from './secret-files.mjs';
test('CI passes only secret paths and cleanup rejects unrelated directories', async () => {
  const parent = await mkdtemp(path.join(tmpdir(), 'banking-ci-unit-'));
  try {
    const envFile = path.join(parent, 'github-env');
    const env = { ...process.env, RUNNER_TEMP: parent, GITHUB_ENV: envFile };
    execFileSync(process.execPath, ['scripts/ci/prepare-secrets.mjs'], { env, windowsHide: true, stdio: 'pipe' });
    const content = await readFile(envFile, 'utf8');
    assert.match(content, /^SECRETS_DIR=[^\r\n]+\n$/);
    const directory = content.trim().slice('SECRETS_DIR='.length);
    assert.throws(() => execFileSync(process.execPath, ['scripts/ci/cleanup-secrets.mjs'],
      { env: { ...env, SECRETS_DIR: parent }, windowsHide: true, stdio: 'pipe' }));
    await stat(directory);
    execFileSync(process.execPath, ['scripts/ci/cleanup-secrets.mjs'],
      { env: { ...env, SECRETS_DIR: directory }, windowsHide: true, stdio: 'pipe' });
    await assert.rejects(stat(directory));
  } finally { await rm(parent, { recursive: true, force: true }); }
});
test('temporary secret files preserve exact bytes and use a private host directory', async () => {
  const parent = await mkdtemp(path.join(tmpdir(), 'banking-secret-unit-'));
  try {
    const directory = path.join(parent, 'files');
    const values = Object.fromEntries(['admin_password', 'app_password', 'migrator_password', 'jwt_secret'].map(n => [n, randomBytes(32).toString('hex')]));
    await writeTestSecrets(directory, values);
    for (const [name, value] of Object.entries(values)) assert.equal(await readFile(path.join(directory, name), 'utf8'), value);
    if (process.platform !== 'win32') assert.equal((await stat(directory)).mode & 0o777, 0o700);
  } finally { await rm(parent, { recursive: true, force: true }); }
});
test('secret mounts reject missing, writable, extra mounts and plaintext environments', () => {
  const mount = { Destination: '/run/secrets/jwt.secret', Type: 'bind', RW: false };
  verifySecretMounts({ Mounts: [mount] }, [mount.Destination]);
  for (const container of [{ Mounts: [] }, { Mounts: [{ ...mount, RW: true }] }, { Mounts: [mount, mount] },
    { Mounts: [mount], Config: { Env: ['JWT_SECRET=synthetic'] } }])
    assert.throws(() => verifySecretMounts(container, [mount.Destination]));
});
test('only exact temporary sources are allowed for secret bind mounts', () => {
  const directory = path.resolve('temporary-secret-fixture');
  const mount = { Destination: '/run/secrets/jwt.secret', Type: 'bind', RW: false, Source: path.join(directory, 'jwt_secret') };
  assert.equal(isTestSecretBind(mount, directory), true);
  assert.equal(isTestSecretBind({ ...mount, Source: path.resolve('other', 'jwt_secret') }, directory), false);
  assert.equal(isTestSecretBind({ ...mount, RW: true }, directory), false);
});
