import { verifySecretMounts } from './secret-files.mjs';
// Never include inspect payloads in errors.
export function verifyMigrationIsolation(migrator, apis) {
  if (!migrator || migrator.State?.Status !== 'exited' || migrator.State?.ExitCode !== 0)
    throw new Error('Migration did not complete successfully');
  if (!apis.length) throw new Error('No runtime containers');
  const migrationEnv = Object.fromEntries((migrator.Config?.Env || []).map(pair => {
    const index = pair.indexOf('='); return [pair.slice(0, index), pair.slice(index + 1)];
  }));
  if (migrationEnv.FLYWAY_PASSWORD_FILE !== '/run/secrets/migrator_password' || !migrationEnv.FLYWAY_USERNAME || !migrationEnv.MIGRATION_DATABASE_URL)
    throw new Error('Missing explicit migration configuration');
  if (migrationEnv.JWT_SECRET || migrationEnv.DATABASE_PASSWORD)
    throw new Error('Unexpected runtime credentials in migrator');
  verifySecretMounts(migrator, ['/run/secrets/migrator_password']);
  if (migrator.Mounts.length !== 1) throw new Error('Unexpected migrator mount');
  for (const api of apis) {
    const env = api.Config?.Env || [];
    if (api.Image !== migrator.Image || !env.includes('SPRING_FLYWAY_ENABLED=false'))
      throw new Error('Migration/runtime image or mode mismatch');
    if (env.some(value => /^(FLYWAY_|MIGRATION_|SPRING_FLYWAY_(USER|PASSWORD|URL)=|DB_ADMIN_|POSTGRES_PASSWORD=)/.test(value)))
      throw new Error('DDL configuration present in runtime');
    verifySecretMounts(api, ['/run/secrets/spring.datasource.password', '/run/secrets/jwt.secret']);
    if (api.Mounts.length !== 2) throw new Error('Unexpected runtime mount');
    if (!env.includes('BANKING_SECRETS_DIRECTORY=/run/secrets/') || !env.includes('SPRING_CONFIG_IMPORT=configtree:/run/secrets/'))
      throw new Error('Required file-only API configuration is missing');
  }
}
