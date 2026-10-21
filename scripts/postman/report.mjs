import { routes } from './build-collection.mjs';

// Allowlist fields from the checked-in collection; never serialize native report data.
// Native reports may contain environment secrets even with bodies/headers omitted.
export function sanitizedReport(raw, collection) {
  const expected = collection.item.flatMap(f => f.item);
  const executions = raw.run?.executions;
  if (!Array.isArray(executions)) throw new Error('Unsupported Postman JSON report format');
  const failures = raw.run.failures || [];
  const safe = executions.map(execution => {
    const item = expected.find(i => i.id === execution.item?.id || i.name === execution.item?.name);
    if (!item) throw new Error('Unknown request in report');
    const metadata = JSON.parse(item.request.description);
    const labels = item.event.filter(e => e.listen === 'test').flatMap(e => e.script.exec)
      .flatMap(line => [...line.matchAll(/pm\.test\((['"])(.*?)\1/g)].map(match => match[2]));
    const assertions = (execution.assertions || []).map(a => ({
      name: labels.includes(a.assertion) ? a.assertion : 'Unknown assertion',
      passed: labels.includes(a.assertion) && !a.error && !a.skipped
    }));
    const status = Number.isInteger(execution.response?.code) ? execution.response.code : null;
    const requestFailure = failures.some(f => f.source?.id === item.id || f.source?.name === item.name);
    const complete = assertions.length === labels.length && new Set(assertions.map(a => a.name)).size === labels.length;
    return { id: item.id, name: item.name, endpoint: metadata.route, expectedStatus: metadata.expectedStatus,
      status, passed: status === metadata.expectedStatus && assertions.length > 0 && complete && !requestFailure && assertions.every(a => a.passed), assertions };
  });
  const endpoints = routes.map(endpoint => {
    const matches = safe.filter(e => e.endpoint === endpoint);
    return { endpoint, requests: matches.length, passed: matches.filter(e => e.passed).length, failed: matches.filter(e => !e.passed).length };
  });
  return { executions: safe, endpoints, requests: safe.length, expectedRequests: expected.length,
    incomplete: safe.length !== expected.length || new Set(safe.map(e => e.id)).size !== expected.length,
    globalErrors: failures.filter(f => !expected.some(i => f.source?.id === i.id || f.source?.name === i.name)).length,
    assertions: safe.reduce((n, e) => n + e.assertions.length, 0),
    failedAssertions: safe.reduce((n, e) => n + e.assertions.filter(a => !a.passed).length, 0),
    scriptOrAssertionFailures: failures.length,
    success: safe.length === expected.length && new Set(safe.map(e => e.id)).size === expected.length && failures.length === 0 && safe.every(e => e.passed) && endpoints.every(e => e.requests > 0)
  };
}

// Only consume sanitized summaries. Never include raw error messages or payloads.
export function junitReport(summary) {
  const escape = value => String(value).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')
    .replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]));
  const executions = summary.executions || [];
  const failures = executions.filter(e => !e.passed).length;
  const issues = [];
  if (summary.infrastructureFailure) issues.push('Infrastructure or interruption');
  if (summary.applicationFailure) issues.push('Recovery application verification');
  if (summary.cleanup === false) issues.push('Cleanup not confirmed');
  if (summary.incomplete || (summary.expectedRequests !== undefined && executions.length !== summary.expectedRequests)
      || summary.requests !== executions.length) issues.push('Incomplete collection execution');
  if (summary.globalErrors > 0) issues.push('Global script error');
  if (!summary.success && failures === 0 && issues.length === 0) issues.push('Run failed without a request-level failure');
  const cases = executions.map(e => `<testcase classname="${escape(e.endpoint)}" name="${escape(e.name)}">${e.passed ? '' : '<failure type="AssertionOrRequestFailure" message="Unexpected status, script error or failed assertion; see summary.json"/>'}</testcase>`);
  cases.push(...issues.map(issue => `<testcase classname="Banking.Postman.Run" name="${issue}"><error type="RunError" message="${issue}; see summary.md"/></testcase>`));
  cases.push(...(summary.recovery?.checks || []).map(name => `<testcase classname="Banking.Recovery" name="${escape(name)}"/>`));
  cases.push(...(summary.migrationChecks || []).map(name => `<testcase classname="Banking.Migrations" name="${escape(name)}"/>`));
  return `<?xml version="1.0" encoding="UTF-8"?><testsuite name="Banking API Postman" tests="${cases.length}" failures="${failures}" errors="${issues.length}">${cases.join('')}</testsuite>\n`;
}
