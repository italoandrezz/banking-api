import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pipelineReports } from '../ci/report.mjs';

const ids = ['node', 'install', 'environment', 'config', 'image', 'security', 'startup', 'smoke', 'cleanup', 'postman'];
const complete = () => Object.fromEntries(ids.map(id => [id, { outcome: 'success' }]));
test('CI reports successful pipeline without publishing arbitrary data', () => {
  const steps = complete(); steps.postman.outputs = { password: 'NEVER_EXPORT_THIS' };
  const reports = pipelineReports(steps);
  assert.match(reports.xml, /errors="0"/);
  assert.doesNotMatch(JSON.stringify(reports), /NEVER_EXPORT_THIS|password/);
});
test('CI reports failed, cancelled, skipped and missing steps as errors', () => {
  for (const outcome of ['failure', 'cancelled', 'skipped', 'untrusted-secret']) {
    const steps = complete(); steps.cleanup.outcome = outcome;
    const reports = pipelineReports(steps);
    assert.match(reports.xml, /errors="1"/);
    assert.match(reports.markdown, /FAIL/);
    assert.doesNotMatch(reports.xml, /untrusted-secret/);
  }
  assert.match(pipelineReports({}).xml, /errors="10"/);
});
