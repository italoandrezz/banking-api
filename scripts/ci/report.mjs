import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Allowlist only static step IDs and enum outcomes. Never serialize native logs.
export function pipelineReports(steps) {
  const ids = ['node', 'install', 'environment', 'config', 'image', 'startup', 'smoke', 'cleanup', 'postman'];
  const rows = ids.map(id => ({ id, outcome: ['success', 'failure', 'cancelled', 'skipped'].includes(steps[id]?.outcome) ? steps[id].outcome : 'missing' }));
  const errors = rows.filter(row => row.outcome !== 'success').length;
  return {
    xml: `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="API pipeline" tests="${rows.length}" failures="0" errors="${errors}">${rows.map(row => `<testcase classname="pipeline" name="${row.id}">${row.outcome === 'success' ? '' : `<error message="${row.outcome}"/>`}</testcase>`).join('')}</testsuite>\n`,
    markdown: `# Pipeline de testes da API\n\nResultado: **${errors ? 'FAIL' : 'PASS'}**\n\n| Etapa | Resultado |\n|---|---|\n${rows.map(row => `| ${row.id} | ${row.outcome} |`).join('\n')}\n\nDetalhes HTTP no relatorio Postman sanitizado, quando disponivel. Logs brutos e ambientes privados nao sao publicados.\n`
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const reports = pipelineReports(JSON.parse(process.env.CI_STEP_RESULTS || '{}'));
  await mkdir('target/ci', { recursive: true });
  await writeFile('target/ci/junit.xml', reports.xml);
  await writeFile('target/ci/summary.md', reports.markdown);
}
