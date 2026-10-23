import { mkdtemp, appendFile, rm } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import { writeTestSecrets } from '../postman/secret-files.mjs';
if (!process.env.RUNNER_TEMP || !process.env.GITHUB_ENV) throw new Error('CI runner environment required');
const parent = await mkdtemp(path.join(process.env.RUNNER_TEMP, 'banking-ci-secrets-'));
try {
  const directory = path.join(parent, 'files');
  await writeTestSecrets(directory, Object.fromEntries(['admin_password', 'app_password', 'migrator_password', 'jwt_secret']
    .map(name => [name, randomBytes(32).toString('hex')])));
  await appendFile(process.env.GITHUB_ENV, `SECRETS_DIR=${directory}\n`);
} catch {
  await rm(parent, { recursive: true, force: true });
  throw new Error('Unable to prepare private CI secrets');
}
