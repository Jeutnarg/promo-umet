import { spawnSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
const app = process.argv[2];
if (!['customer','admin'].includes(app)) throw Error('Expected customer or admin');
const result=spawnSync(process.execPath,['node_modules/vite/bin/vite.js','build','--outDir',`dist/${app}`],{stdio:'inherit'});
if(result.status!==0)process.exit(result.status || 1);
const config=await readFile('public/config.js','utf8');
await writeFile(`dist/${app}/config.js`,config.replace(/app:\s*"customer"/,`app: "${app}"`));
