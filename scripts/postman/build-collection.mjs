// Editable source of the generated, importable Postman v2.1 collection. No npm dependencies.
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { hasSensitiveFields } from './sensitive-fields.mjs';

export const routes = [
  'POST /auth/login', 'GET /auth/me', 'POST /customers', 'GET /customers',
  'GET /customers/{id}', 'PUT /customers/{id}', 'DELETE /customers/{id}',
  'POST /accounts', 'GET /accounts', 'GET /accounts/{id}',
  'PATCH /accounts/{id}/block', 'PATCH /accounts/{id}/unblock', 'PATCH /accounts/{id}/close',
  'POST /accounts/{id}/deposit', 'POST /accounts/{id}/withdraw', 'POST /accounts/transfer',
  'GET /accounts/{id}/transactions', 'POST /admin/transactions/{id}/reversal'
];
const folders = [];
let folder;
let counter = 0;
function group(name) { folder = { name, item: [] }; folders.push(folder); }
const v = name => `{{${name}}}`;
const capture = (key, expression) => `pm.variables.set(${JSON.stringify(key)}, ${expression});`;
const check = (label, expression) => `pm.test(${JSON.stringify(label)}, () => pm.expect(Boolean(${expression})).to.equal(true));`;
function request(name, method, path, status, { auth = 'tokenA', body, tests = [], headers = {}, before = '' } = {}) {
  const route = `${method} ${path.split('?')[0].replace(/\{\{[^}]+\}\}/g, '{id}')}`;
  const id = `banking-${String(++counter).padStart(3, '0')}`;
  const scripts = [
    `pm.test('HTTP ${status}', () => pm.expect(pm.response.code).to.equal(${status}));`,
    ...(status === 204 ? [check('Empty response body', "pm.response.text().length === 0")] : [
      "let data; pm.test('Valid JSON response', () => { data = pm.response.json(); pm.expect(data !== null).to.equal(true); });",
      check('JSON content type', "((pm.response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase() === 'application/json') || ((pm.response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase().startsWith('application/') && (pm.response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase().endsWith('+json'))"),
      ...(status >= 400 ? [
        check('Error envelope', `data && data.status === ${status} && ['timestamp','error','message','path'].every(k => typeof data[k] === 'string')`),
        check('Error path matches request', 'data && data.path === pm.variables.replaceIn(pm.request.url.getPath())')
      ] : []),
      ...(path !== '/v3/api-docs' ? [
        hasSensitiveFields.toString(),
        check('No sensitive JSON properties in response', `!hasSensitiveFields(data, ${method === 'POST' && path === '/auth/login' && status === 200})`)
      ] : [])
    ]),
    ...tests
  ];
  const req = { method, url: '{{baseUrl}}' + path,
    auth: auth ? { type: 'bearer', bearer: [{ key: 'token', value: v(auth), type: 'string' }] } : { type: 'noauth' },
    header: Object.entries({ ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers })
      .map(([key, value]) => ({ key, value })),
    description: JSON.stringify({ route, expectedStatus: status })
  };
  if (body !== undefined) req.body = { mode: 'raw', raw: typeof body === 'string' ? body : JSON.stringify(body), options: { raw: { language: 'json' } } };
  folder.item.push({ id, name, request: req, event: [
    ...(before ? [{ listen: 'prerequest', script: { type: 'text/javascript', exec: before.split('\n') } }] : []),
    { listen: 'test', script: { type: 'text/javascript', exec: scripts } }
  ] });
}
const customer = who => ({ fullName: 'Postman Test', cpf: v(`cpf${who}`), email: v(`email${who}`), password: v(`password${who}`), phone: '81999999999', birthDate: '2000-01-01' });
const profile = { fullName: 'Updated Postman Test', email: v('emailA'), phone: '81988888888', birthDate: '2000-01-01' };
const amount = value => ({ amount: value });
const transfer = value => ({ sourceAccountId: v('accountA'), destinationAccountId: v('accountB'), amount: value });
function balance(who, expected, label = 'Stored balance') {
  request(label + ' ' + who, 'GET', `/accounts/${v('account' + who)}`, 200, { auth: 'token' + who,
    tests: [check('Expected balance in cents', `data && Math.round(data.balance * 100) === ${Math.round(expected * 100)}`)] });
}

group('01 - Disponibilidade');
request('Health sem detalhes internos', 'GET', '/actuator/health', 200, { auth: null,
  before: `for (const who of ['A','B','C']) {
    const id = pm.variables.replaceIn('{{$randomUUID}}').replace(/-/g, '');
    pm.variables.set('email' + who, 'postman-' + id + '@example.test');
    pm.variables.set('password' + who, pm.variables.replaceIn('{{$randomUUID}}'));
    pm.variables.set('cpf' + who, String(parseInt(id.slice(0, 12), 16) % 90000000000 + 10000000000));
  }
  pm.variables.set('missingId', pm.variables.replaceIn('{{$randomUUID}}'));
  pm.variables.set('badToken', 'invalid.jwt.token');
  for (const key of ['depositKey','withdrawKey','transferKey']) pm.variables.set(key, pm.variables.replaceIn('{{$randomUUID}}'));`,
  tests: [check('Health UP only', "data.status === 'UP' && Object.keys(data).length === 1")] });
request('Inventario OpenAPI das 18 rotas', 'GET', '/v3/api-docs', 200, { auth: null,
  tests: [check('Exact business route inventory', `JSON.stringify(Object.entries(data.paths).flatMap(([p, verbs]) => Object.keys(verbs).filter(m => ['get','post','put','patch','delete'].includes(m)).map(m => m.toUpperCase() + ' ' + p)).sort()) === JSON.stringify(${JSON.stringify([...routes].sort())})`)] });

group('02 - Cadastro e validacoes');
for (const who of ['A','B','C']) request('Cadastrar cliente ' + who, 'POST', '/customers', 201, { auth: null,
  body: { ...customer(who), role: 'ADMIN', balance: 9999 }, tests: [
    check('Customer response fields', "data && typeof data.id === 'string' && typeof data.createdAt === 'string' && data.email === pm.variables.get('email" + who + "')"),
    capture('customer' + who, 'data.id')
  ] });
request('CPF duplicado', 'POST', '/customers', 409, { auth: null, body: { ...customer('A'), email: 'unused-{{missingId}}@example.test' } });
request('Email duplicado', 'POST', '/customers', 409, { auth: null, body: { ...customer('A'), cpf: '12345678901' } });
for (const [name, patch] of [
  ['Nome vazio', { fullName: '' }], ['Nome longo', { fullName: 'x'.repeat(121) }],
  ['CPF invalido', { cpf: '123' }], ['Email invalido', { email: 'invalid' }],
  ['Senha curta', { password: 'short' }], ['Senha acima de 72 bytes', { password: 'é'.repeat(37) }],
  ['Data futura', { birthDate: '9999-12-31' }], ['Data fora da faixa', { birthDate: '+10000-01-01' }],
  ['Caractere nulo', { fullName: 'test\u0000name' }], ['Telefone longo', { phone: '1'.repeat(21) }]
]) request(name, 'POST', '/customers', 400, { auth: null, body: { ...customer('A'), ...patch } });
request('JSON malformado', 'POST', '/customers', 400, { auth: null, body: '{' });
request('Campos obrigatorios ausentes', 'POST', '/customers', 400, { auth: null, body: {} });

group('03 - Autenticacao');
for (const who of ['A','B','C','Admin']) request('Login ' + who, 'POST', '/auth/login', 200, { auth: null,
  body: { email: who === 'Admin' ? v('adminEmail') : v('email' + who), password: who === 'Admin' ? v('adminPassword') : v('password' + who) },
  tests: [check('JWT response', "data && typeof data.token === 'string' && data.token.split('.').length === 3"), capture('token' + who, 'data.token')] });
request('Senha incorreta', 'POST', '/auth/login', 401, { auth: null, body: { email: v('emailA'), password: 'wrong-password' } });
request('Usuario inexistente', 'POST', '/auth/login', 401, { auth: null, body: { email: 'missing-{{missingId}}@example.test', password: 'wrong-password' } });
request('Login invalido', 'POST', '/auth/login', 400, { auth: null, body: { email: 'invalid', password: '' } });
request('Identidade sem escalacao por mass assignment', 'GET', '/auth/me', 200, { tests: [check('Own CUSTOMER identity', "data.customerId === pm.variables.get('customerA') && data.role === 'CUSTOMER'")] });
request('Identidade ADMIN de fixture isolada', 'GET', '/auth/me', 200, { auth: 'tokenAdmin', tests: [check('ADMIN identity', "data.role === 'ADMIN'"), capture('adminId', 'data.customerId')] });
request('JWT expirado com assinatura valida', 'GET', '/auth/me', 401, { auth: 'expiredToken' });
request('JWT adulterado', 'GET', '/auth/me', 401, { auth: 'tamperedToken', before: "pm.variables.set('tamperedToken', pm.variables.get('tokenA') + 'x');" });

group('04 - Clientes e titularidade');
request('Consultar proprio cliente', 'GET', '/customers/{{customerA}}', 200, { tests: [check('Own customer only', "data.id === pm.variables.get('customerA')")] });
request('Atualizar proprio perfil', 'PUT', '/customers/{{customerA}}', 200, { body: { ...profile, role: 'ADMIN' }, tests: [check('Profile updated', "data.fullName === 'Updated Postman Test'")] });
request('Atualizacao nao promove papel', 'GET', '/auth/me', 200, { tests: [check('Still CUSTOMER', "data.role === 'CUSTOMER'")] });
request('Listagem geral negada por contrato', 'GET', '/customers', 403);
for (const method of ['GET','PUT','DELETE']) request(method + ' cliente alheio', method, '/customers/{{customerB}}', 403, { body: method === 'PUT' ? profile : undefined });
request('Cliente inexistente', 'GET', '/customers/{{missingId}}', 404);
request('UUID de cliente invalido', 'GET', '/customers/not-a-uuid', 400);
request('Atualizacao invalida', 'PUT', '/customers/{{customerA}}', 400, { body: { ...profile, birthDate: '9999-12-31' } });
request('Atualizacao com email duplicado', 'PUT', '/customers/{{customerA}}', 409, { body: { ...profile, email: v('emailB') } });
request('Perfil preservado apos rejeicoes', 'GET', '/customers/{{customerA}}', 200, { tests: [check('Profile unchanged after rejection', "data.email === pm.variables.get('emailA') && data.fullName === 'Updated Postman Test'")] });

group('05 - Contas');
for (const who of ['A','B']) request('Criar conta ' + who, 'POST', '/accounts', 201, { auth: 'token' + who, body: { customerId: v('customer' + who), balance: 999 }, tests: [
  check('New owned active account with zero balance', `data.customerId === pm.variables.get('customer${who}') && data.status === 'ACTIVE' && data.balance === 0 && typeof data.accountNumber === 'string'`), capture('account' + who, 'data.id')
] });
request('Criar conta de outro cliente', 'POST', '/accounts', 403, { body: { customerId: v('customerB') } });
request('Criar conta sem titular', 'POST', '/accounts', 400, { body: {} });
request('Criar conta de titular inexistente', 'POST', '/accounts', 404, { body: { customerId: v('missingId') } });
request('Listar somente contas proprias', 'GET', '/accounts', 200, { tests: [check('Only owned accounts returned', "Array.isArray(data) && data.length === 1 && data.every(a => a.customerId === pm.variables.get('customerA'))")] });
balance('A', 0);
request('Consultar conta alheia', 'GET', '/accounts/{{accountB}}', 403);
request('Conta inexistente', 'GET', '/accounts/{{missingId}}', 404);
request('UUID de conta invalido', 'GET', '/accounts/invalid', 400);

group('06 - Matriz de autenticacao');
for (const route of routes.filter(r => !['POST /customers','POST /auth/login'].includes(r))) {
  const [method, template] = route.split(' ');
  const path = template.replace('{id}', template.startsWith('/customers') ? v('customerA') : template.startsWith('/admin') ? v('missingId') : v('accountA'));
  const body = method === 'PUT' ? profile : method === 'POST' ? (template === '/accounts' ? { customerId: v('customerA') } : template === '/accounts/transfer' ? transfer(1) : template.startsWith('/admin') ? { reason: 'test' } : amount(1)) : undefined;
  for (const auth of [null, 'badToken']) request(`${auth ? 'JWT invalido' : 'Sem JWT'} - ${route}`, method, path, 401, { auth, body });
}

group('07 - Operacoes financeiras e idempotencia');
request('Depositar 100', 'POST', '/accounts/{{accountA}}/deposit', 200, { body: amount(100), headers: { 'Idempotency-Key': v('depositKey') }, tests: [check('Balance after deposit', 'data.balance === 100')] });
request('Repetir deposito sem duplicar', 'POST', '/accounts/{{accountA}}/deposit', 200, { body: amount(100), headers: { 'Idempotency-Key': v('depositKey') }, tests: [check('Replay balance', 'data.balance === 100')] });
request('Chave de deposito com outro valor', 'POST', '/accounts/{{accountA}}/deposit', 409, { body: amount(101), headers: { 'Idempotency-Key': v('depositKey') } });
request('Chave invalida', 'POST', '/accounts/{{accountA}}/deposit', 400, { body: amount(1), headers: { 'Idempotency-Key': 'invalid key' } });
request('Mesma chave em outro titular', 'POST', '/accounts/{{accountB}}/deposit', 200, { auth: 'tokenB', body: amount(50), headers: { 'Idempotency-Key': v('depositKey') }, tests: [check('Independent customer key', 'data.balance === 50')] });
for (const repeat of [false, true]) request(repeat ? 'Repetir saque sem duplicar' : 'Sacar 20', 'POST', '/accounts/{{accountA}}/withdraw', 200, { body: amount(20), headers: { 'Idempotency-Key': v('withdrawKey') }, tests: [check('Balance after withdrawal/replay', 'data.balance === 80')] });
for (const repeat of [false, true]) request(repeat ? 'Repetir transferencia sem duplicar' : 'Transferir 30 entre titulares', 'POST', '/accounts/transfer', 200, { body: transfer(30), headers: { 'Idempotency-Key': v('transferKey') }, tests: [check('Source balance after transfer/replay', 'data.balance === 50')] });
request('Transferencia com chave conflitante', 'POST', '/accounts/transfer', 409, { body: transfer(31), headers: { 'Idempotency-Key': v('transferKey') } });
request('Saque sem saldo', 'POST', '/accounts/{{accountA}}/withdraw', 409, { body: amount(1000) });
request('Transferencia sem saldo', 'POST', '/accounts/transfer', 409, { body: transfer(1000) });
request('Transferencia para mesma conta', 'POST', '/accounts/transfer', 409, { body: { ...transfer(1), destinationAccountId: v('accountA') } });
request('Destino inexistente', 'POST', '/accounts/transfer', 404, { body: { ...transfer(1), destinationAccountId: v('missingId') } });
request('Origem alheia', 'POST', '/accounts/transfer', 403, { body: { sourceAccountId: v('accountB'), destinationAccountId: v('accountA'), amount: 1 } });
for (const operation of ['deposit','withdraw']) request(operation + ' em conta alheia', 'POST', `/accounts/{{accountB}}/${operation}`, 403, { body: amount(1) });
for (const operation of ['deposit','withdraw','transfer']) {
  for (const [name, value] of [['zero',0],['negativo',-1],['escala',0.001],['excessivo',99999999999999],['nulo',null],['texto','invalid']]) {
    request(`${operation} valor ${name}`, 'POST', operation === 'transfer' ? '/accounts/transfer' : `/accounts/{{accountA}}/${operation}`, 400, { body: operation === 'transfer' ? transfer(value) : amount(value) });
  }
}
balance('A', 50, 'Saldo apos operacoes rejeitadas');
balance('B', 80, 'Saldo apos operacoes rejeitadas');

group('08 - Extrato');
request('Extrato origem sem duplicatas', 'GET', '/accounts/{{accountA}}/transactions', 200, { tests: [
  check('Statement page structure and total', 'Array.isArray(data.content) && data.page === 0 && data.size === 20 && data.totalElements === 3 && data.totalPages === 1'),
  check('Financial history values', "data.content.some(t => t.type === 'TRANSFER' && t.amount === 30) && data.content.some(t => t.type === 'WITHDRAW' && t.amount === 20) && data.content.some(t => t.type === 'DEPOSIT' && t.amount === 100)"),
  ...['DEPOSIT','WITHDRAW','TRANSFER'].map(type => capture(type.toLowerCase() + 'Id', `data.content.find(t => t.type === '${type}').id`))
] });
request('Extrato destino', 'GET', '/accounts/{{accountB}}/transactions', 200, { auth: 'tokenB', tests: [check('Received transfer and deposit', "data.totalElements === 2 && data.content.some(t => t.type === 'TRANSFER' && t.destinationAccountId === pm.variables.get('accountB') && t.amount === 30)"), capture('depositBId', "data.content.find(t => t.type === 'DEPOSIT').id")] });
request('Extrato paginado', 'GET', '/accounts/{{accountA}}/transactions?page=1&size=1', 200, { tests: [check('Pagination metadata', 'data.page === 1 && data.size === 1 && data.content.length === 1 && data.totalElements === 3 && data.totalPages === 3')] });
request('Filtrar tipo e datas inclusivas', 'GET', '/accounts/{{accountA}}/transactions?type=TRANSFER&startDate=0001-01-01&endDate=9999-12-31', 200, { tests: [check('Filtered transfer', "data.totalElements === 1 && data.content[0].type === 'TRANSFER'")] });
request('Extrato alheio', 'GET', '/accounts/{{accountB}}/transactions', 403);
for (const query of ['page=-1','size=0','size=101','type=INVALID','startDate=2025-02-01&endDate=2025-01-01','startDate=invalid']) request('Filtro invalido ' + query, 'GET', '/accounts/{{accountA}}/transactions?' + query, 400);

group('09 - Estornos administrativos');
request('CUSTOMER nao estorna', 'POST', '/admin/transactions/{{transferId}}/reversal', 403, { body: { reason: 'test' } });
for (const [name, reason] of [['vazio',''],['nulo',null],['longo','x'.repeat(256)],['caractere nulo','test\u0000reason']]) request('Motivo ' + name, 'POST', '/admin/transactions/{{transferId}}/reversal', 400, { auth: 'tokenAdmin', body: { reason } });
request('Estorno de transacao inexistente', 'POST', '/admin/transactions/{{missingId}}/reversal', 404, { auth: 'tokenAdmin', body: { reason: 'test' } });
for (const [original, inverse, a, b] of [['transfer','TRANSFER',80,50],['withdraw','DEPOSIT',100,50],['deposit','WITHDRAW',0,50]]) {
  request('Estornar ' + original, 'POST', `/admin/transactions/${v(original + 'Id')}/reversal`, 201, { auth: 'tokenAdmin', body: { reason: 'Postman isolated reversal' }, tests: [
    check('Reversal audit metadata', `data.originalTransactionId === pm.variables.get('${original}Id') && data.type === '${inverse}' && data.adminId === pm.variables.get('adminId') && data.reason === 'Postman isolated reversal'`),
    capture('reversalId', 'data.id')
  ] });
  balance('A', a, 'Saldo apos estorno ' + original);
  balance('B', b, 'Saldo apos estorno ' + original);
}
request('Estorno duplicado', 'POST', '/admin/transactions/{{transferId}}/reversal', 409, { auth: 'tokenAdmin', body: { reason: 'repeat' } });
request('Estorno de estorno', 'POST', '/admin/transactions/{{reversalId}}/reversal', 409, { auth: 'tokenAdmin', body: { reason: 'repeat' } });
request('Vinculos no extrato', 'GET', '/accounts/{{accountA}}/transactions', 200, { tests: [check('Three originals linked to three reversals', 'data.totalElements === 6 && data.content.filter(t => t.originalTransactionId).length === 3 && data.content.filter(t => t.reversalTransactionId).length === 3')] });
request('Sacar saldo do destino', 'POST', '/accounts/{{accountB}}/withdraw', 200, { auth: 'tokenB', body: amount(50) });
request('Estorno sem saldo', 'POST', '/admin/transactions/{{depositBId}}/reversal', 409, { auth: 'tokenAdmin', body: { reason: 'insufficient' } });
balance('B', 0, 'Estorno rejeitado preserva saldo');
request('Restaurar saldo destino para testar estados', 'POST', '/accounts/{{accountB}}/deposit', 200, { auth: 'tokenB', body: amount(50) });

group('10 - Estados de conta');
for (const operation of ['block','unblock','close']) request(operation + ' conta alheia', 'PATCH', `/accounts/{{accountB}}/${operation}`, 403);
request('Bloquear conta ativa', 'PATCH', '/accounts/{{accountA}}/block', 200, { tests: [check('BLOCKED status', "data.status === 'BLOCKED'")] });
request('Bloqueio duplicado', 'PATCH', '/accounts/{{accountA}}/block', 409);
for (const operation of ['deposit','withdraw']) request(operation + ' bloqueada', 'POST', `/accounts/{{accountA}}/${operation}`, 409, { body: amount(1) });
request('Transferencia origem bloqueada', 'POST', '/accounts/transfer', 409, { body: transfer(1) });
request('Transferencia destino bloqueado', 'POST', '/accounts/transfer', 409, { auth: 'tokenB', body: { sourceAccountId: v('accountB'), destinationAccountId: v('accountA'), amount: 1 } });
request('Encerramento bloqueada', 'PATCH', '/accounts/{{accountA}}/close', 409);
request('Desbloquear conta', 'PATCH', '/accounts/{{accountA}}/unblock', 200, { tests: [check('ACTIVE status', "data.status === 'ACTIVE'")] });
request('Desbloqueio duplicado', 'PATCH', '/accounts/{{accountA}}/unblock', 409);
request('Encerrar conta zerada', 'PATCH', '/accounts/{{accountA}}/close', 200, { tests: [check('CLOSED zero balance', "data.status === 'CLOSED' && data.balance === 0")] });
request('Nao reabrir encerrada', 'PATCH', '/accounts/{{accountA}}/unblock', 409);
request('Deposito em encerrada', 'POST', '/accounts/{{accountA}}/deposit', 409, { body: amount(1) });
request('Extrato continua acessivel apos encerramento', 'GET', '/accounts/{{accountA}}/transactions', 200, { tests: [check('History preserved', 'data.totalElements === 6')] });
request('Nao encerrar conta com saldo', 'PATCH', '/accounts/{{accountB}}/close', 409, { auth: 'tokenB' });
balance('B', 50, 'Saldo preservado apos transicoes rejeitadas');

group('11 - Exclusao');
request('Excluir cliente sem contas', 'DELETE', '/customers/{{customerC}}', 204, { auth: 'tokenC' });
request('Token do cliente excluido rejeitado', 'GET', '/auth/me', 401, { auth: 'tokenC' });
request('Excluir cliente com contas', 'DELETE', '/customers/{{customerA}}', 409);
request('Excluir cliente inexistente', 'DELETE', '/customers/{{missingId}}', 404);

group('12 - Limite de saldo e retry apos falha');
request('Conta separada para limite monetario', 'POST', '/accounts', 201, { body: { customerId: v('customerA') }, tests: [capture('limitAccount', 'data.id')] });
request('Saque sem saldo com chave', 'POST', '/accounts/{{limitAccount}}/withdraw', 409, { body: amount(1), headers: { 'Idempotency-Key': 'retry-{{withdrawKey}}' } });
request('Depositar no limite NUMERIC', 'POST', '/accounts/{{limitAccount}}/deposit', 200, { body: amount('9999999999999.99'), tests: [check('Exact decimal serialized without floating point rounding', "pm.response.text().includes('9999999999999.99')")] });
request('Deposito excederia saldo maximo', 'POST', '/accounts/{{limitAccount}}/deposit', 409, { body: amount('0.01') });
request('Transferencia excederia destino', 'POST', '/accounts/transfer', 409, { auth: 'tokenB', body: { sourceAccountId: v('accountB'), destinationAccountId: v('limitAccount'), amount: 1 } });
balance('B', 50, 'Overflow rejeitado preserva origem');
request('Overflow rejeitado preserva destino', 'GET', '/accounts/{{limitAccount}}', 200, { tests: [check('Maximum balance unchanged', "pm.response.text().includes('9999999999999.99')")] });
request('Retry de saque anteriormente rejeitado', 'POST', '/accounts/{{limitAccount}}/withdraw', 200, { body: amount(1), headers: { 'Idempotency-Key': 'retry-{{withdrawKey}}' }, tests: [check('Rejected request did not claim key permanently', "pm.response.text().includes('9999999999998.99')")] });
request('Replay do retry sem novo debito', 'POST', '/accounts/{{limitAccount}}/withdraw', 200, { body: amount(1), headers: { 'Idempotency-Key': 'retry-{{withdrawKey}}' }, tests: [check('Retry replay is stable', "pm.response.text().includes('9999999999998.99')")] });

export const collection = {
  info: { name: 'Banking API - isolated endpoint regression', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
    description: 'Run with scripts/Test-Postman.ps1. Requires a disposable Docker stack and an ADMIN fixture. No real data or shared environments.' },
  event: [{ listen: 'prerequest', script: { type: 'text/javascript', exec: [
    "if (!/^http:\\/\\/127\\.0\\.0\\.1:\\d+$/.test(pm.environment.get('baseUrl') || '') || !/^banking-postman-[a-f0-9]+$/.test(pm.environment.get('isolationId') || '')) { throw new Error('Use the isolated Docker runner; baseUrl/isolationId are required.'); }"
  ] } }],
  item: folders
};
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  writeFileSync(new URL('../../postman/banking-api.postman_collection.json', import.meta.url), JSON.stringify(collection, null, 2) + '\n');
  console.log(`Generated ${counter} requests in ${folders.length} folders.`);
}
