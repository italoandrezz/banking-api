import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { collection } from './build-collection.mjs';
import { sanitizedReport, junitReport } from './report.mjs';
import { hasSensitiveFields } from './sensitive-fields.mjs';

test('JUnit records completed migration isolation checks separately from HTTP requests', () => {
  const xml = junitReport({ success: true, requests: 0, executions: [], cleanup: true,
    migrationChecks: ['Migration gate', 'Same image', 'No DDL credentials'] });
  assert.match(xml, /tests="3" failures="0" errors="0"/);
  assert.equal((xml.match(/classname="Banking.Migrations"/g) || []).length, 3);
  const failed = junitReport({ success: false, requests: 0, executions: [], cleanup: true,
    infrastructureFailure: 'migration-failure-gate', migrationChecks: [] });
  assert.match(failed, /errors="1"/);
});

test('Sensitive fields are rejected regardless of JSON whitespace and escaped property names', () => {
  for (const text of ['{"password":"synthetic"}', '{ "password" : "synthetic" }', '{\n "passwordHash"\t:\n null\n}', '{"pass\\u0077ord":"synthetic"}']) {
    assert.equal(hasSensitiveFields(JSON.parse(text)), true);
  }
});
test('Sensitive fields are found recursively in objects and arrays', () => {
  for (const value of [{ profile: { password: null } }, [{ id: 1 }, { nested: [{ passwordHash: 'synthetic' }] }], { values: [[{ secret: '' }]] }]) {
    assert.equal(hasSensitiveFields(value), true);
  }
});
test('Equivalent sensitive property names are normalized exactly', () => {
  for (const key of ['PASSWORD', 'password_hash', 'hashed-password', 'passwd', 'pwd', 'senha', 'clientSecret', 'JWT_SECRET', 'privateKey', 'api-key', 'access_token', 'refreshToken']) {
    assert.equal(hasSensitiveFields({ [key]: 'synthetic' }), true, key);
  }
});
test('Legitimate names, primitive values and error messages are not false positives', () => {
  for (const value of [null, 42, 'password', [], { tokenType: 'Bearer', passwordChangedAt: '2026-01-01', hasPassword: true, passwordPolicy: { minLength: 8 }, message: 'Invalid password', description: 'secret', accountNumber: '123' }]) {
    assert.equal(hasSensitiveFields(value), false);
  }
});
test('Login permits only its contracted root token, never nested secrets or other tokens', () => {
  assert.equal(hasSensitiveFields({ token: 'synthetic' }, true), false);
  for (const value of [{ token: 'synthetic', password: '' }, { nested: { token: 'synthetic' } }, [{ token: 'synthetic' }], { accessToken: 'synthetic' }]) {
    assert.equal(hasSensitiveFields(value, true), true);
  }
  assert.equal(hasSensitiveFields({ token: 'synthetic' }), true);
});
test('Generated Postman assertion uses the recursive implementation on success and error responses', () => {
  for (const item of collection.item.flatMap(folder => folder.item)) {
    const meta = JSON.parse(item.request.description);
    const script = item.event.find(event => event.listen === 'test').script.exec;
    if (meta.expectedStatus === 204 || meta.route === 'GET /v3/api-docs') continue;
    const assertion = script.find(line => line.includes("pm.test(\"No sensitive JSON properties"));
    assert.ok(assertion, item.name);
    const evaluate = data => new Function('pm', 'data', hasSensitiveFields.toString() + '\n' + assertion)({
      test: (name, fn) => fn(), expect: value => ({ to: { equal: expected => assert.equal(value, expected) } })
    }, data);
    assert.throws(() => evaluate({ nested: [{ password: 'synthetic' }] }), item.name);
    assert.doesNotThrow(() => evaluate({ message: 'Invalid password', passwordChangedAt: null }), item.name);
  }
});

function junitCounts(summary) {
  const xml = junitReport(summary);
  const header = xml.match(/<testsuite\b[^>]*>/)[0];
  const counts = Object.fromEntries(['tests', 'failures', 'errors'].map(key => [key, Number(header.match(new RegExp(key + '="(\\d+)"'))[1])]));
  assert.equal(counts.tests, [...xml.matchAll(/<testcase\b/g)].length);
  assert.equal(counts.failures, [...xml.matchAll(/<failure\b/g)].length);
  assert.equal(counts.errors, [...xml.matchAll(/<error\b/g)].length);
  return counts;
}
function completeSummary() { return { ...sanitizedReport(nativeReport(), collection), cleanup: true }; }
test('JUnit complete run has consistent counts and no failures', () => {
  assert.deepEqual(junitCounts(completeSummary()), { tests: 185, failures: 0, errors: 0 });
});
test('JUnit assertion failures are represented as failed test cases', () => {
  const raw = nativeReport(); raw.run.executions[0].assertions[0].error = { message: 'SENSITIVE_MARKER' };
  const summary = { ...sanitizedReport(raw, collection), cleanup: true };
  assert.deepEqual(junitCounts(summary), { tests: 185, failures: 1, errors: 0 });
  assert.equal(junitReport(summary).includes('SENSITIVE_MARKER'), false);
});
test('JUnit global script errors cannot report success', () => {
  const raw = nativeReport(); raw.run.failures.push({ source: { id: 'collection' }, error: { message: 'SENSITIVE_MARKER' } });
  const summary = { ...sanitizedReport(raw, collection), cleanup: true };
  assert.deepEqual(junitCounts(summary), { tests: 186, failures: 0, errors: 1 });
  assert.equal(junitReport(summary).includes('SENSITIVE_MARKER'), false);
});
test('JUnit partial, empty and duplicated executions contain an error', () => {
  for (const mutate of [raw => raw.run.executions.pop(), raw => { raw.run.executions = []; }, raw => { raw.run.executions[1] = raw.run.executions[0]; }]) {
    const raw = nativeReport(); mutate(raw);
    const summary = { ...sanitizedReport(raw, collection), cleanup: true };
    assert.equal(summary.success, false);
    assert.ok(junitCounts(summary).errors > 0);
  }
});
test('JUnit infrastructure failures and interruptions contain errors without exposing details', () => {
  for (const stage of ['startup', 'interrupted', 'private-file-cleanup']) {
    const summary = { ...completeSummary(), success: false, infrastructureFailure: stage };
    assert.equal(junitCounts(summary).errors, 1);
  }
});
test('JUnit cleanup failures and unexplained CLI failures cannot be green', () => {
  for (const patch of [{ cleanup: false }, {}]) {
    assert.ok(junitCounts({ ...completeSummary(), ...patch, success: false }).errors > 0);
  }
});
test('JUnit XML escapes reserved characters and removes forbidden control characters', () => {
  const summary = completeSummary(); summary.executions[0].name = 'a<&"\'\u0000';
  const xml = junitReport(summary);
  assert.ok(xml.includes('a&lt;&amp;&quot;&apos;'));
  assert.equal(xml.includes('\u0000'), false);
});
test('VS Code tasks use the running editor executable without installation-specific paths', () => {
  const tasks = JSON.parse(readFileSync(new URL('../../.vscode/tasks.json', import.meta.url), 'utf8')).tasks;
  const open = tasks.find(task => task.label === 'Postman: abrir resultados');
  assert.equal(open.type, 'process');
  assert.equal(open.command, '${execPath}');
  assert.equal(open.windows, undefined);
  assert.deepEqual(open.args, ['--reuse-window', '${workspaceFolder}/target/postman/latest/summary.md']);
  for (const task of tasks.filter(task => task !== open)) {
    assert.equal(task.command, 'node');
    assert.equal(task.args[0], '${workspaceFolder}/scripts/postman/run.mjs');
  }
});

test('Checked-in collection matches its generator and contains valid scripts', () => {
  const saved = JSON.parse(readFileSync(new URL('../../postman/banking-api.postman_collection.json', import.meta.url), 'utf8'));
  assert.deepEqual(saved, collection);
  for (const owner of [collection, ...collection.item.flatMap(folder => folder.item)]) {
    for (const event of owner.event || []) new Function('pm', event.script.exec.join('\n'));
  }
});

function nativeReport() {
  return { run: { failures: [], executions: collection.item.flatMap(f => f.item).map(item => ({
    item: { id: item.id, name: item.name }, response: { code: JSON.parse(item.request.description).expectedStatus },
    assertions: item.event.filter(e => e.listen === 'test').flatMap(e => e.script.exec)
      .flatMap(line => [...line.matchAll(/pm\.test\((['"])(.*?)\1/g)].map(match => ({ assertion: match[2] })))
  })) } };
}
test('Complete report requires every route, request and declared assertion', () => {
  const report = sanitizedReport(nativeReport(), collection);
  assert.equal(report.success, true);
  assert.equal(report.endpoints.length, 18);
});
test('No native credentials, bodies, headers or failure values leak to the report', () => {
  const raw = nativeReport();
  const marker = 'SENSITIVE_TEST_MARKER';
  raw.environment = { values: [{ key: 'password', value: marker }] };
  const execution = raw.run.executions[0];
  execution.request = { header: [{ value: marker }], body: marker };
  execution.response.stream = marker;
  execution.response.header = [{ value: marker }];
  execution.assertions[0].error = { actual: marker, message: marker, stack: marker };
  execution.assertions.push({ assertion: marker, error: { message: marker } });
  raw.run.failures.push({ error: { message: marker }, source: execution.item });
  const report = sanitizedReport(raw, collection);
  assert.equal(report.success, false);
  assert.equal(JSON.stringify(report).includes(marker), false);
});
test('Skipped or omitted assertions fail the run', () => {
  for (const mutate of [raw => raw.run.executions[0].assertions.pop(), raw => { raw.run.executions[0].assertions[0].skipped = true; }]) {
    const raw = nativeReport(); mutate(raw);
    assert.equal(sanitizedReport(raw, collection).success, false);
  }
});
test('Incomplete or duplicated requests cannot produce a false pass', () => {
  const raw = nativeReport(); raw.run.executions.pop();
  assert.equal(sanitizedReport(raw, collection).success, false);
  raw.run.executions.push(raw.run.executions[0]);
  assert.equal(sanitizedReport(raw, collection).success, false);
});
test('Runtime script failure marks its request as failed even if assertions passed', () => {
  const raw = nativeReport(); raw.run.failures.push({ source: raw.run.executions[0].item, error: { name: 'TypeError' } });
  const report = sanitizedReport(raw, collection);
  assert.equal(report.success, false);
  assert.equal(report.executions[0].passed, false);
});
test('Unexpected status fails independently of assertions', () => {
  const raw = nativeReport(); raw.run.executions[0].response.code = 500;
  assert.equal(sanitizedReport(raw, collection).success, false);
});
test('Collection refuses remote and uninitialized environments', () => {
  const script = new Function('pm', collection.event[0].script.exec.join('\n'));
  for (const [baseUrl, isolationId] of [['https://example.com', 'banking-postman-ab12'], ['http://127.0.0.1:8080', ''], ['', '']]) {
    assert.throws(() => script({ environment: { get: key => ({ baseUrl, isolationId })[key] } }));
  }
  script({ environment: { get: key => ({ baseUrl: 'http://127.0.0.1:12345', isolationId: 'banking-postman-ab12' })[key] } });
});
