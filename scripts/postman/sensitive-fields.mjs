// Self-contained so the same implementation runs in Node tests and Postman scripts.
// Match property names, not substrings or values: passwordChangedAt/tokenType are legitimate.
export function hasSensitiveFields(value, allowLoginToken = false) {
  const forbidden = new Set([
    'password', 'passwordhash', 'hashedpassword', 'passwd', 'pwd', 'senha', 'senhahash',
    'secret', 'clientsecret', 'jwtsecret', 'signingkey', 'privatekey', 'apikey',
    'token', 'accesstoken', 'refreshtoken'
  ]);
  function visit(node, root) {
    if (node === null || typeof node !== 'object') return false;
    if (Array.isArray(node)) return node.some(item => visit(item, false));
    return Object.entries(node).some(([key, child]) => {
      const normalized = key.toLowerCase().replace(/[_\s-]/g, '');
      const allowed = root && allowLoginToken && key === 'token';
      return (forbidden.has(normalized) && !allowed) || visit(child, false);
    });
  }
  return visit(value, true);
}
