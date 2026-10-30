import { readFile, writeFile } from 'node:fs/promises';
const spec=JSON.parse(await readFile('../backend/target/openapi.json','utf8'));
const lines=[`// Generated from Spring OpenAPI. Run npm run generate; do not edit.`, `import type { operations } from './schema';`, `type Transport = <T>(path: string, method?: string, body?: unknown, headers?: Record<string,string>) => Promise<T>;`, `export function createClient(transport: Transport) { return {`];
for(const [path,methods] of Object.entries(spec.paths))for(const [method,operation] of Object.entries(methods)) {
  if(!operation.operationId)continue;
  const id=operation.operationId;
  const args=[];
  const hasPath=operation.parameters?.some(p=>p.in==='path');
  const hasHeaders=operation.parameters?.some(p=>p.in==='header');
  if(hasPath)args.push(`path: operations[${JSON.stringify(id)}]['parameters']['path']`);
  if(operation.requestBody)args.push(`body: NonNullable<operations[${JSON.stringify(id)}]['requestBody']>['content']['application/json']`);
  if(hasHeaders)args.push(`headers: NonNullable<operations[${JSON.stringify(id)}]['parameters']['header']>`);
  const status=Object.keys(operation.responses).find(s=>s.startsWith('2'));
  const media=Object.keys(operation.responses[status].content || {})[0];
  const result=media?`operations[${JSON.stringify(id)}]['responses'][${status}]['content'][${JSON.stringify(media)}]`:'void';
  let route=JSON.stringify(path.replace(/^\/api\/v1/,''));
  for(const parameter of operation.parameters||[])if(parameter.in==='path')route=`${route}.replace(${JSON.stringify('{'+parameter.name+'}')}, encodeURIComponent(String(path[${JSON.stringify(parameter.name)}])))`;
  lines.push(`  ${JSON.stringify(id)}: (${args.join(', ')}) => transport<${result}>(${route}, ${JSON.stringify(method.toUpperCase())}, ${operation.requestBody?'body':'undefined'}, ${hasHeaders?'headers':'undefined'}),`);
}
lines.push('}; }');
await writeFile('src/generated/client.ts',lines.join('\n')+'\n');
