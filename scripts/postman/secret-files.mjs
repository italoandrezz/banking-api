import { mkdir, writeFile, chmod } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

export async function writeTestSecrets(directory, values) {
  await mkdir(directory, { recursive: false, mode: 0o700 });
  if (process.platform === 'win32') {
    const account = `${process.env.USERDOMAIN}\\${process.env.USERNAME}`;
    try {
      execFileSync('icacls.exe', [directory, '/inheritance:r', '/grant:r', `${account}:(OI)(CI)F`, '*S-1-5-18:(OI)(CI)F'],
        { windowsHide: true, stdio: 'pipe' });
    } catch { throw new Error('Unable to restrict temporary secrets directory'); }
  }
  for (const name of ['admin_password', 'app_password', 'migrator_password', 'jwt_secret']) {
    if (typeof values[name] !== 'string' || !/^[a-f0-9]{64}$/.test(values[name])) throw new Error('Expected random test secret');
    const file = path.join(directory, name);
    await writeFile(file, values[name], { mode: 0o600, flag: 'wx' });
    // Host parent is 0700; mounted file must be readable by container UID 10001/postgres.
    if (process.platform !== 'win32') await chmod(file, 0o444);
  }
}

export function verifySecretMounts(container, targets) {
  const mounts = (container.Mounts || []).filter(m => m.Destination.startsWith('/run/secrets/'));
  if (mounts.length !== targets.length || mounts.some(m => m.RW || m.Type !== 'bind' || !targets.includes(m.Destination)))
    throw new Error('Secret mount isolation failed');
  if ((container.Config?.Env || []).some(e => /^(DATABASE_PASSWORD|POSTGRES_PASSWORD|FLYWAY_PASSWORD|JWT_SECRET|SPRING_DATASOURCE_PASSWORD|SPRING_FLYWAY_PASSWORD)=/.test(e)))
    throw new Error('Plaintext secret variable in container configuration');
}

export function isTestSecretBind(mount, directory) {
  const filename = { 'spring.datasource.password': 'app_password', 'jwt.secret': 'jwt_secret',
    admin_password: 'admin_password', app_password: 'app_password', migrator_password: 'migrator_password' }[mount.Destination.split('/').at(-1)];
  if (!filename || mount.RW || mount.Type !== 'bind' || !mount.Destination.startsWith('/run/secrets/')) return false;
  const normalize = value => value.replaceAll('\\', '/').replace(/^\/(?:run\/desktop\/mnt\/host|host_mnt)\/([a-z])\//i, '$1:/')
    .replace(/^([A-Z]):/, (_, drive) => drive.toLowerCase() + ':');
  return normalize(mount.Source) === normalize(path.resolve(directory, filename));
}
