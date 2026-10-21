// Never include inspect payloads in errors: Docker environment values are secrets.
export function verifyMigrationIsolation(migrator, apis) {
  if (!migrator || migrator.State?.Status !== 'exited' || migrator.State?.ExitCode !== 0)
    throw new Error('Migration did not complete successfully');
  if (!apis.length) throw new Error('No runtime containers');
  const migrationEnv = Object.fromEntries((migrator.Config?.Env || []).map(pair => {
    const index = pair.indexOf('='); return [pair.slice(0, index), pair.slice(index + 1)];
  }));
  if (!migrationEnv.FLYWAY_PASSWORD || !migrationEnv.FLYWAY_USERNAME || !migrationEnv.MIGRATION_DATABASE_URL)
    throw new Error('Missing explicit migration configuration');
  if (migrationEnv.JWT_SECRET || migrationEnv.DATABASE_PASSWORD)
    throw new Error('Unexpected runtime credentials in migrator');
  for (const api of apis) {
    const env = api.Config?.Env || [];
    if (api.Image !== migrator.Image || !env.includes('SPRING_FLYWAY_ENABLED=false'))
      throw new Error('Migration/runtime image or mode mismatch');
    if (env.some(value => /^(FLYWAY_|MIGRATION_|SPRING_FLYWAY_(USER|PASSWORD|URL)=|DB_ADMIN_|POSTGRES_PASSWORD=)/.test(value)))
      throw new Error('DDL configuration present in runtime');
    if (env.some(value => value.slice(value.indexOf('=') + 1) === migrationEnv.FLYWAY_PASSWORD))
      throw new Error('Migration secret present in runtime');
  }
}
