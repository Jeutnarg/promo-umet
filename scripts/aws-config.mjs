import { readFile, writeFile } from 'node:fs/promises';
const [outputFile,app,destination] = process.argv.slice(2);
if (!['customer','admin'].includes(app)||!destination) throw Error('Usage: node scripts/aws-config.mjs terraform-outputs.json customer|admin destination/config.js');
const outputs=JSON.parse(await readFile(outputFile,'utf8'));
await writeFile(destination, `window.__CONFIG__ = ${JSON.stringify({...outputs.frontend_config.value,app})};\n`);
