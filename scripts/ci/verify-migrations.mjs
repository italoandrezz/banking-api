import { execFileSync } from 'node:child_process';
import { verifyMigrationIsolation } from '../postman/migration-isolation.mjs';

try {
  if (!process.env.COMPOSE_PROJECT_NAME) throw new Error('Explicit project required');
  const docker = args => execFileSync('docker', args, { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], timeout: 30_000 }).trim();
  const migrator = docker(['compose', 'ps', '--all', '-q', 'migrate']);
  const apis = docker(['compose', 'ps', '-q', 'api']).split(/\s+/).filter(Boolean);
  const inspected = JSON.parse(docker(['inspect', migrator, ...apis]));
  verifyMigrationIsolation(inspected[0], inspected.slice(1));
  console.log('Migration succeeded; API uses the same image without DDL credentials.');
} catch {
  console.error('Migration isolation verification failed; native output withheld.');
  process.exitCode = 1;
}
