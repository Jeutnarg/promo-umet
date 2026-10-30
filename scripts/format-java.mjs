import { readdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const checkOnly = process.argv.includes('--check');
const formatterJar = process.env.GOOGLE_JAVA_FORMAT_JAR;
if (!formatterJar) throw new Error('Set GOOGLE_JAVA_FORMAT_JAR to google-java-format 1.28.0-all-deps.jar.');
async function findJavaFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(entry => entry.isDirectory() ? findJavaFiles(resolve(directory, entry.name)) : entry.name.endsWith('.java') ? [resolve(directory, entry.name)] : []));
  return nested.flat();
}
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
let differences = false;
for (const file of await findJavaFiles('backend/src')) {
  const original = await readFile(file, 'utf8');
  const normalized = original.replace(/\r\n/g, '\n').replace(/^\t+/gm, tabs => '  '.repeat(tabs.length));
  const result = spawnSync(java, ['-jar', formatterJar, '-'], { input: normalized, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr || `Formatting failed: ${file}`);
  const formatted = result.stdout.replace(/\r\n/g, '\n').replace(/^(?: {2})+/gm, spaces => '\t'.repeat(spaces.length / 2));
  if (original.replace(/\r\n/g, '\n') !== formatted) {
    differences = true;
    if (checkOnly) console.error(`Formatting differs: ${file}`);
    else await writeFile(file, formatted);
  }
}
if (checkOnly && differences) process.exitCode = 1;
