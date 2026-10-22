import { randomBytes } from 'node:crypto';
import { chmod, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { discoverHealthyApi, assertRecoveryIsolation } from './recovery.mjs';

// Called only for the unique tmpfs Postman project, after all financial/recovery checks.
export async function verifySecretRotation({ command, compose, dbId, directory, email, password, customerId, oldDbPassword, testEnv, onStage = () => {} }) {
  onStage('isolation');
  const inspected = JSON.parse((await command('docker', ['inspect', dbId])).stdout)[0];
  let baseUrl = await discoverHealthyApi(compose);
  assertRecoveryIsolation(inspected, baseUrl);
  async function login() {
    const response = await fetch(baseUrl + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }), signal: AbortSignal.timeout(15000) });
    if (response.status !== 200) throw new Error('Rotation login failed');
    return (await response.json()).token;
  }
  onStage('initial-login');
  const oldToken = await login();
  const replace = async (name, value) => {
    const file = path.join(directory, name);
    if (process.platform !== 'win32') await chmod(file, 0o600);
    await writeFile(file, value);
    if (process.platform !== 'win32') await chmod(file, 0o444);
  };
  onStage('replace-secrets');
  await replace('jwt_secret', randomBytes(32).toString('hex'));
  const newPassword = randomBytes(32).toString('hex');
  await command('docker', ['exec', '-i', dbId, 'psql', '-X', '-U', 'postman_admin', '-d', 'banking_postman_test', '-v', 'ON_ERROR_STOP=1'],
    { input: `ALTER ROLE postman_test PASSWORD '${newPassword}';\n` });
  await replace('app_password', newPassword);
  onStage('recreate-api');
  await compose(['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '180', 'api']);
  baseUrl = await discoverHealthyApi(compose);
  onStage('reject-old-jwt');
  const oldResponse = await fetch(baseUrl + '/customers/' + customerId, { headers: { Authorization: 'Bearer ' + oldToken }, signal: AbortSignal.timeout(15000) });
  if (oldResponse.status !== 401) throw new Error('Old signing key still accepted');
  onStage('accept-new-credentials');
  const token = await login();
  const response = await fetch(baseUrl + '/customers/' + customerId, { headers: { Authorization: 'Bearer ' + token }, signal: AbortSignal.timeout(15000) });
  if (response.status !== 200) throw new Error('Rotated credentials not operational');
  onStage('reject-old-database-password');
  // Loopback uses initdb's trust rule; the service address exercises host SCRAM authentication.
  const rejected = await command('docker', ['exec', '-i', '-e', 'PGPASSWORD', dbId, 'psql', '-X', '-h', 'db',
    '-U', 'postman_test', '-d', 'banking_postman_test', '-c', 'SELECT 1'], { allowFailure: true, env: { ...testEnv, PGPASSWORD: oldDbPassword } });
  if (rejected.code === 0 || !rejected.stderr.includes('password authentication failed')) throw new Error('Old database password not rejected');
  return ['JWT rotation rejects old tokens and accepts new tokens', 'Database rotation renews API connections and rejects old password'];
}
