import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
const app = process.argv[2] || "customer";
const port = Number(process.argv[3] || (app === "admin" ? 3001 : 3000));
const offset = Number(process.env.LOCAL_PORT_OFFSET || 0);
if (!Number.isInteger(offset) || offset < 0 || offset > 50000)
  throw new Error("Invalid LOCAL_PORT_OFFSET");
const root = resolve("frontend/dist");
const config = {
  app,
  api: `http://localhost:${8081 + offset}`,
  authority: `http://localhost:${8080 + offset}/realms/sandbox`,
  clientId: "sandbox-web",
  scope: "openid profile email",
  adminUrl: `http://localhost:${3001 + offset}`,
  customerUrl: `http://localhost:${3000 + offset}`,
};
const mime = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".css": "text/css",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".json": "application/json",
};
createServer(async (req, res) => {
  const pathname = new URL(req.url, "http://localhost").pathname;
  if (pathname === "/config.js") {
    res.setHeader("Content-Type", "text/javascript");
    res.setHeader("Cache-Control", "no-store");
    res.end(`window.__CONFIG__ = ${JSON.stringify(config)};`);
    return;
  }
  const path = resolve(root, "." + decodeURIComponent(pathname));
  if (path !== root && !path.startsWith(root + sep)) {
    res.writeHead(403);
    res.end();
    return;
  }
  try {
    const data = await readFile(path);
    res.setHeader(
      "Content-Type",
      mime[extname(path)] || "application/octet-stream",
    );
    res.end(data);
  } catch {
    res.setHeader("Content-Type", "text/html");
    res.end(await readFile(resolve(root, "index.html")));
  }
}).listen(port, "127.0.0.1", () =>
  console.log(`${app}: http://localhost:${port}`),
);
