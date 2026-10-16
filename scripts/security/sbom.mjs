import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Maven already resolved versions, scopes and conflict mediation. Do not resolve them again remotely.
export function dependencySbom(tree) {
  const components = new Map();
  function visit(node, root = false) {
    for (const key of ['groupId', 'artifactId', 'version']) {
      if (typeof node[key] !== 'string' || !/^[A-Za-z0-9_.+-]+$/.test(node[key])) throw new Error('Invalid Maven coordinate');
    }
    if (node.children !== undefined && !Array.isArray(node.children)) throw new Error('Invalid Maven tree');
    if (!root) {
      const purl = `pkg:maven/${node.groupId}/${node.artifactId}@${node.version}`;
      components.set(purl, { type: 'library', group: node.groupId, name: node.artifactId, version: node.version, purl, 'bom-ref': purl });
    }
    for (const child of node.children || []) visit(child);
  }
  visit(tree, true);
  if (!components.size) throw new Error('Empty Maven dependency tree');
  return { bomFormat: 'CycloneDX', specVersion: '1.5', version: 1, components: [...components.values()] };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const bom = dependencySbom(JSON.parse(await readFile('target/dependency-tree.json', 'utf8')));
  await mkdir('target/security', { recursive: true });
  await writeFile('target/security/maven.cdx.json', JSON.stringify(bom, null, 2) + '\n');
  console.log(`Maven SBOM: ${bom.components.length} resolved dependency coordinates, including test scope.`);
}
