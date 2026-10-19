import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluateScan, securityReports } from '../security/policy.mjs';
import { dependencySbom } from '../security/sbom.mjs';

test('Resolved Maven inventory includes transitive and test dependencies without arbitrary metadata', () => {
  const leaf = { groupId: 'org.example', artifactId: 'test-library', version: '1.2.3', scope: 'test', private: 'DO_NOT_EXPORT' };
  const tree = { groupId: 'com.example', artifactId: 'app', version: '1.0', children: [{ ...leaf, children: [leaf] }] };
  const bom = dependencySbom(tree);
  assert.equal(bom.components.length, 1);
  assert.equal(bom.components[0].purl, 'pkg:maven/org.example/test-library@1.2.3');
  assert.doesNotMatch(JSON.stringify(bom), /DO_NOT_EXPORT/);
  assert.throws(() => dependencySbom({ ...tree, children: [] }));
  assert.throws(() => dependencySbom({ ...tree, children: [{ ...leaf, version: 'unresolved value' }] }));
});
test('Maven scan must cover every coordinate in the resolved inventory', () => {
  const report = scan([]); report.Results[1].Packages = [{ Name: 'org.example:lib', Version: '1.0' }];
  assert.doesNotThrow(() => evaluateScan(report, { ...options, kind: 'maven', expectedCoordinates: ['org.example:lib@1.0'] }));
  assert.throws(() => evaluateScan(report, { ...options, kind: 'maven', expectedCoordinates: ['org.example:lib@2.0'] }));
});

const finding = () => ({ VulnerabilityID: 'CVE-2026-47884', PkgName: 'org.springframework:spring-webmvc', InstalledVersion: '6.2.19', Severity: 'CRITICAL' });
const scan = vulnerabilities => ({ SchemaVersion: 2, Results: [{ Class: 'os-pkgs', Type: 'ubuntu' }, { Class: 'lang-pkgs', Type: 'jar', Vulnerabilities: vulnerabilities }] });
const options = { kind: 'image', source: '@RestController class Example {}', now: new Date('2026-10-15') };

test('Nginx requires OS inventory with nginx and preserves vulnerability thresholds', () => {
  const report = { SchemaVersion: 2, Results: [{ Class: 'os-pkgs', Packages: [{ Name: 'nginx', Version: '1.30.6-r1' }] }] };
  assert.deepEqual(evaluateScan(report, { kind: 'nginx' }), []);
  for (const Packages of [undefined, [], [{ Name: 'other', Version: '1' }]])
    assert.throws(() => evaluateScan({ ...report, Results: [{ Class: 'os-pkgs', Packages }] }, { kind: 'nginx' }));
  assert.throws(() => evaluateScan(report, { kind: 'unknown' }));
  assert.throws(() => evaluateScan(report, { kind: 'image' }));
  for (const Severity of ['UNKNOWN', 'HIGH', 'CRITICAL', 'MEDIUM']) {
    report.Results[0].Vulnerabilities = [{ ...finding(), PkgName: 'zlib', Severity, FixedVersion: '1.3.2-r1' }];
    assert.equal(evaluateScan(report, { kind: 'nginx' })[0].blocked, true);
  }
});
test('Security gate rejects missing, malformed and incomplete scanner output', () => {
  for (const report of [{}, { SchemaVersion: 2, Results: [] }, { SchemaVersion: 2, Results: [{ Class: 'os-pkgs' }] }]) assert.throws(() => evaluateScan(report, options));
  assert.throws(() => evaluateScan(scan([{ ...finding(), Severity: 'invented' }]), options));
  assert.throws(() => evaluateScan(scan([]), { ...options, kind: 'maven' }));
});
test('Spring exception is constrained by version, expiry and absence of vulnerable features', () => {
  assert.equal(evaluateScan(scan([finding()]), options)[0].blocked, false);
  for (const source of ['', 'XsltView', 'SseEmitter', 'ModelAndView', '@Controller', 'text/event-stream']) assert.equal(evaluateScan(scan([finding()]), { ...options, source })[0].blocked, true);
  assert.equal(evaluateScan(scan([finding()]), { ...options, now: new Date('2026-11-15') })[0].blocked, true);
  assert.equal(evaluateScan(scan([{ ...finding(), InstalledVersion: '6.2.18' }]), options)[0].blocked, true);
});
test('Security gate blocks relevant findings even without a published fix', () => {
  for (const Severity of ['HIGH', 'CRITICAL', 'UNKNOWN']) assert.equal(evaluateScan(scan([{ ...finding(), VulnerabilityID: 'CVE-2026-12345', Severity }]), options)[0].blocked, true);
  assert.equal(evaluateScan(scan([{ ...finding(), Severity: 'MEDIUM', PkgName: 'example', FixedVersion: '1.2.3' }]), options)[0].blocked, true);
});
test('Scanner infrastructure errors cannot generate green JUnit or Markdown', () => {
  assert.match(securityReports([], true).xml, /errors="1"/);
  assert.match(securityReports([], true).markdown, /FAIL/);
  const rows = evaluateScan(scan([{ ...finding(), PkgName: 'example' }]), options);
  assert.match(securityReports(rows).xml, /failures="1"/);
});
test('Sanitized security reports omit arbitrary scanner data', () => {
  const rows = evaluateScan(scan([{ ...finding(), Description: 'PRIVATE_TOKEN', References: ['PRIVATE_SECRET'] }]), options);
  assert.doesNotMatch(JSON.stringify(securityReports(rows)), /PRIVATE_/);
});
