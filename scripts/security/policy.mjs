// Exceptions are narrowly scoped and expire. They do not assert patched libraries.
const springIds = new Set(['CVE-2026-47884', 'CVE-2026-47890']);
export function evaluateScan(report, { kind, source = '', now = new Date(), expectedCoordinates = [] }) {
  if (!['image', 'maven', 'nginx'].includes(kind)) throw new Error('Unsupported scan kind');
  if (report.SchemaVersion !== 2 || !Array.isArray(report.Results) || !report.Results.length) throw new Error('Invalid scanner report');
  if (kind === 'nginx' && !report.Results.some(r => r.Class === 'os-pkgs' && Array.isArray(r.Packages)
      && r.Packages.some(p => p.Name === 'nginx' && p.Version))) throw new Error('Incomplete Nginx coverage');
  if (kind === 'image' && (!report.Results.some(r => r.Class === 'os-pkgs') || !report.Results.some(r => r.Class === 'lang-pkgs' && r.Type === 'jar'))) throw new Error('Incomplete image coverage');
  if (kind === 'maven' && !report.Results.some(r => r.Class === 'lang-pkgs' && r.Type === 'jar')) throw new Error('Incomplete Maven coverage');
  if (kind === 'maven') {
    const scanned = new Set(report.Results.flatMap(r => r.Packages || []).map(p => `${p.Name}@${p.Version}`));
    if (!expectedCoordinates.length || expectedCoordinates.some(p => !scanned.has(p))) throw new Error('Incomplete resolved dependency coverage');
  }
  const forbiddenViews = /XsltView|SseEmitter|ResponseBodyEmitter|ModelAndView|FragmentsRendering|ViewResolver|text\/event-stream|TEXT_EVENT_STREAM|spring\.mvc\.view|@Controller\b/;
  const rows = [];
  for (const result of report.Results) {
    if (result.Vulnerabilities !== undefined && !Array.isArray(result.Vulnerabilities)) throw new Error('Invalid vulnerabilities');
    for (const v of result.Vulnerabilities || []) {
      if (!/^CVE-\d{4}-\d+$|^GHSA-[a-z0-9-]+$/.test(v.VulnerabilityID || '')
          || !/^[a-zA-Z0-9_.:+/@-]+$/.test(v.PkgName || '')
          || !/^[a-zA-Z0-9_.:+~/-]+$/.test(v.InstalledVersion || '')
          || !['UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(v.Severity)) throw new Error('Invalid vulnerability fields');
      const exception = springIds.has(v.VulnerabilityID) && v.PkgName === 'org.springframework:spring-webmvc'
        && v.InstalledVersion === '6.2.19' && source.length > 0 && !forbiddenViews.test(source)
        && now < new Date('2026-11-15T00:00:00Z');
      const blocked = !exception && (['UNKNOWN', 'HIGH', 'CRITICAL'].includes(v.Severity) || (v.Severity === 'MEDIUM' && !!v.FixedVersion));
      rows.push({ id: v.VulnerabilityID, pkg: v.PkgName, version: v.InstalledVersion, severity: v.Severity,
        decision: exception ? 'not-applicable-until-2026-11-15' : blocked ? 'blocked' : 'residual-monitor', blocked });
    }
  }
  return rows;
}

export function securityReports(rows, infrastructureError = false) {
  const failures = rows.filter(r => r.blocked).length;
  // All values originate from validated identifiers, never arbitrary scanner messages.
  const escape = s => String(s).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
  const cases = rows.map(r => `<testcase classname="security" name="${escape(r.id + ' ' + r.pkg + ' ' + r.version)}">${r.blocked ? '<failure message="Security policy rejected vulnerability"/>' : ''}</testcase>`);
  cases.push(`<testcase classname="security" name="scanner-completion">${infrastructureError ? '<error message="Scanner, coverage or cleanup failure"/>' : ''}</testcase>`);
  return { xml: `<?xml version="1.0" encoding="UTF-8"?><testsuite name="Security" tests="${cases.length}" failures="${failures}" errors="${infrastructureError ? 1 : 0}">${cases.join('')}</testsuite>\n`,
    markdown: `# Seguranca de dependencias e imagem\n\nResultado: **${failures || infrastructureError ? 'FAIL' : 'PASS'}**\n\nScanner/estrutura/limpeza: ${infrastructureError ? 'FAIL' : 'PASS'}\n\n| CVE | Pacote | Versao | Severidade | Decisao |\n|---|---|---|---|---|\n${rows.map(r => `| ${r.id} | ${r.pkg} | ${r.version} | ${r.severity} | ${r.decision} |`).join('\n')}\n\nPolitica e justificativas: docs/dependency-security.md. PASS significa atendimento a politica, nao ausencia de vulnerabilidades.\n` };
}
