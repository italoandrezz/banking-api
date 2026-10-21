import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyMigrationIsolation } from './migration-isolation.mjs';
const fixture = () => ({ migrator: { Image: 'same-image', State: { Status: 'exited', ExitCode: 0 },
  Config: { Env: ['FLYWAY_USERNAME=migrator', 'FLYWAY_PASSWORD=synthetic-secret', 'MIGRATION_DATABASE_URL=jdbc:postgresql://db/test'] } },
  api: { Image: 'same-image', Config: { Env: ['SPRING_FLYWAY_ENABLED=false', 'DATABASE_PASSWORD=synthetic-runtime'] } } });
test('completed migrator shares image without sharing DDL credentials', () => {
  const { migrator, api } = fixture(); verifyMigrationIsolation(migrator, [api, api]);
});
test('running, failed and missing migrator fail closed', () => {
  for (const state of [{ Status: 'running', ExitCode: 0 }, { Status: 'exited', ExitCode: 1 }]) {
    const { migrator, api } = fixture(); migrator.State = state;
    assert.throws(() => verifyMigrationIsolation(migrator, [api]));
  }
  assert.throws(() => verifyMigrationIsolation(undefined, []));
});
test('runtime rejects DDL variables including empty values and aliased secrets', () => {
  for (const value of ['FLYWAY_PASSWORD=', 'FLYWAY_USERNAME=migrator', 'SPRING_FLYWAY_PASSWORD=synthetic-secret',
    'MIGRATION_DATABASE_URL=jdbc:postgresql://db', 'OTHER=synthetic-secret']) {
    const { migrator, api } = fixture(); api.Config.Env.push(value);
    assert.throws(() => verifyMigrationIsolation(migrator, [api]), error => !error.message.includes('synthetic-secret'));
  }
});
test('missing runtime, differing image and enabled runtime migrations fail', () => {
  const { migrator, api } = fixture();
  assert.throws(() => verifyMigrationIsolation(migrator, []));
  api.Image = 'other'; assert.throws(() => verifyMigrationIsolation(migrator, [api]));
  api.Image = migrator.Image; api.Config.Env = ['SPRING_FLYWAY_ENABLED=true'];
  assert.throws(() => verifyMigrationIsolation(migrator, [api]));
});
