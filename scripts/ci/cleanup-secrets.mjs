import { rm } from 'node:fs/promises';
import path from 'node:path';
const directory = process.env.SECRETS_DIR;
if (!directory || !process.env.RUNNER_TEMP) throw new Error('Missing CI secrets location');
const parent = path.dirname(path.resolve(directory));
if (path.basename(directory) !== 'files' || path.dirname(parent) !== path.resolve(process.env.RUNNER_TEMP)
    || !path.basename(parent).startsWith('banking-ci-secrets-')) throw new Error('Unsafe CI secrets location');
await rm(parent, { recursive: true, force: true });
