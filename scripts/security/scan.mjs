import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile, readdir, copyFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateScan, securityReports } from './policy.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const image = process.argv[2];
if (!/^sha256:[a-f0-9]{64}$/.test(image || '')) throw new Error('An immutable local image ID is required');
const scanner = 'aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa';
const privateDir = await mkdtemp(path.join(tmpdir(), 'banking-security-'));
const cache = path.join(root, 'target/security-cache');
const output = path.join(root, 'target/security');
const name = 'banking-security-' + path.basename(privateDir).slice('banking-security-'.length).toLowerCase();
let child;
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { interrupted = true; child?.kill(); });
function command(args, allowFailure = false, capture = false) {
  return new Promise((resolve, reject) => {
    const proc = spawn('docker', args, { cwd: root, stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true }); child = proc;
    let stdout = ''; proc.stdout.on('data', chunk => { if (capture) stdout += chunk; });
    const timer = setTimeout(() => proc.kill(), 900_000);
    proc.on('error', () => { clearTimeout(timer); reject(new Error('Scanner infrastructure failure')); });
    proc.on('close', code => { clearTimeout(timer); child = undefined; code === 0 || allowFailure ? resolve(capture ? stdout : code) : reject(new Error('Scanner infrastructure failure')); });
  });
}
async function sources(dir) {
  let text = '';
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) text += await sources(file);
    else if (/\.(java|properties|yml|yaml|xml)$/.test(entry.name)) text += '\n' + await readFile(file, 'utf8');
  }
  return text;
}
let rows = []; let failed = false; let stage = 'preparation';
try {
  await mkdir(cache, { recursive: true }); await mkdir(output, { recursive: true });
  await copyFile(path.join(output, 'maven.cdx.json'), path.join(privateDir, 'maven.cdx.json'));
  const bom = JSON.parse(await readFile(path.join(privateDir, 'maven.cdx.json'), 'utf8'));
  if (bom.bomFormat !== 'CycloneDX' || !Array.isArray(bom.components) || !bom.components.length) throw new Error('Invalid Maven inventory');
  const expectedCoordinates = bom.components.map(p => `${p.group}:${p.name}@${p.version}`);
  await command(['image', 'save', '-o', path.join(privateDir, 'image.tar'), image]);
  const common = ['run', '--rm', '--name', name, '-v', `${privateDir}:/audit`, '-v', `${cache}:/cache`, scanner];
  for (const kind of ['image', 'maven']) {
    if (interrupted) throw new Error('Interrupted');
    console.log('Scanning ' + kind + ' with pinned Trivy 0.75.0...');
    const args = kind === 'image' ? ['image', '--input', '/audit/image.tar'] : ['sbom', '/audit/maven.cdx.json'];
    stage = kind + '-scan';
    await command([...common, ...args, '--cache-dir', '/cache', '--scanners', 'vuln', '--list-all-pkgs', '--format', 'json', '--output', `/audit/${kind}.json`, '--timeout', '10m']);
    stage = kind + '-report-validation';
    const raw = JSON.parse(await readFile(path.join(privateDir, kind + '.json'), 'utf8'));
    rows.push(...evaluateScan(raw, { kind, source: await sources(path.join(root, 'src/main')), expectedCoordinates }));
  }
} catch { failed = true; console.error(`Security scan failed during ${stage}; native output withheld.`); }
finally {
  try {
    // Remove only this invocation's scanner, including after interruption.
    await command(['rm', '-f', name], true);
    const remaining = await command(['ps', '-aq', '--filter', `name=^/${name}$`], false, true);
    if (remaining.trim()) throw new Error('Scanner remained');
    if (path.dirname(privateDir) !== path.resolve(tmpdir()) || !path.basename(privateDir).startsWith('banking-security-')) throw new Error('Unsafe private directory');
    await rm(privateDir, { recursive: true, force: true });
  } catch { failed = true; }
  failed ||= interrupted;
  const reports = securityReports(rows, failed);
  await mkdir(output, { recursive: true });
  await writeFile(path.join(output, 'junit.xml'), reports.xml);
  await writeFile(path.join(output, 'summary.md'), reports.markdown);
  process.exitCode = failed || rows.some(r => r.blocked) ? 1 : 0;
  console.log(`Security policy: ${process.exitCode ? 'FAIL' : 'PASS'}; ${rows.length} reported findings. Reports: target/security.`);
}
