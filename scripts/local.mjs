import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import {
  access,
  mkdir,
  open,
  readFile,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { createServer } from "node:net";
import { dirname, join, resolve } from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const local = join(root, ".local");
const windows = process.platform === "win32";
const java = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, "bin", windows ? "java.exe" : "java")
  : "java";
const offset = Number(process.env.LOCAL_PORT_OFFSET || 0);
if (!Number.isInteger(offset) || offset < 0 || offset > 50000)
  throw new Error("LOCAL_PORT_OFFSET must be an integer from 0 to 50000.");
const ports = {
  customer: 3000 + offset,
  admin: 3001 + offset,
  dynamodb: 8000 + offset,
  keycloak: 8080 + offset,
  backend: 8081 + offset,
  management: 9000 + offset,
};
const urls = Object.fromEntries(
  Object.entries(ports).map(([name, port]) => [
    name,
    `http://localhost:${port}`,
  ]),
);
const keycloak = join(local, "tools", "keycloak-26.8.1");
const dynamodb = join(local, "tools", "dynamodb-2.6.1");
const data = join(local, "data", String(offset));
const logs = join(local, "logs", String(offset));
const backendJar = join(
  root,
  "backend",
  "target",
  "promotion-sandbox-1.0.0.jar",
);
const environment = {
  ...process.env,
  SPRING_PROFILES_ACTIVE: "local",
  DYNAMO_ENDPOINT: urls.dynamodb,
  OIDC_ISSUER: `${urls.keycloak}/realms/sandbox`,
  OIDC_JWKS: `${urls.keycloak}/realms/sandbox/protocol/openid-connect/certs`,
  OIDC_AUDIENCE: "sandbox-api",
  CORS_ORIGINS: `${urls.customer},${urls.admin}`,
  TABLE_NAME: "promotion-sandbox",
  AWS_REGION: "us-east-1",
  AWS_EC2_METADATA_DISABLED: "true",
  KC_BOOTSTRAP_ADMIN_USERNAME: "local-admin",
  KC_BOOTSTRAP_ADMIN_PASSWORD: "local-admin-password",
  KC_DB: "dev-file",
  KC_DB_URL: `jdbc:h2:file:${join(data, "keycloak", "keycloakdb").replaceAll("\\", "/")};NON_KEYWORDS=VALUE`,
  KC_HEALTH_ENABLED: "true",
  LOCAL_PORT_OFFSET: String(offset),
};
const children = [];
let stopping;

function prepareCommand(command, args) {
  if (windows && /\.(cmd|bat)$/i.test(command)) {
    const literal = (value) => `'${value.replaceAll("'", "''")}'`;
    return [
      "powershell.exe",
      [
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        `& ${literal(command)} ${args.map(literal).join(" ")}; exit $LASTEXITCODE`,
      ],
    ];
  }
  return [command, args];
}

async function run(command, args, cwd = root, capture = false) {
  const [executable, parameters] = prepareCommand(command, args);
  return new Promise((resolveResult, reject) => {
    const child = spawn(executable, parameters, {
      cwd,
      env: environment,
      windowsHide: true,
      stdio: capture ? ["ignore", "pipe", "pipe"] : "inherit",
    });
    let output = "";
    if (capture) {
      child.stdout.on("data", (bytes) => {
        output += bytes;
      });
      child.stderr.on("data", (bytes) => {
        output += bytes;
      });
    }
    child.once("error", reject);
    child.once("exit", (code) =>
      code === 0
        ? resolveResult(output)
        : reject(
            new Error(
              `${command} exited with ${code}${capture ? `: ${output.trim()}` : ""}`,
            ),
          ),
    );
  });
}

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

async function checkPrerequisites() {
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major < 22 || (major === 22 && minor < 12))
    throw new Error("Install Node.js 22.12 or newer, including npm.");
  const version = await run(java, ["-version"], root, true).catch(() => {
    throw new Error(
      "Install JDK 21 and set JAVA_HOME (or put Java 21 on PATH).",
    );
  });
  if (!/version "21[.\"]/.test(version))
    throw new Error(
      `JDK 21 is required. Current runtime: ${version.split("\n")[0]}. Set JAVA_HOME to JDK 21.`,
    );
  console.log(`Java: ${version.split("\n")[0]}`);
  console.log(`Node: ${process.version}`);
}

async function checksum(path) {
  const hash = createHash("sha256");
  for await (const bytes of createReadStream(path)) hash.update(bytes);
  return hash.digest("hex");
}

async function installArchive(
  name,
  url,
  digest,
  destination,
  marker,
  extractionRoot,
) {
  if (await exists(marker)) {
    console.log(`${name}: already installed`);
    return;
  }
  const archive = join(local, "downloads", `${name}.tar.gz`);
  await mkdir(dirname(archive), { recursive: true });
  if (!(await exists(archive)) || (await checksum(archive)) !== digest) {
    console.log(`Downloading ${name} (public download; no account required)…`);
    const response = await fetch(url, { signal: AbortSignal.timeout(300000) });
    if (!response.ok || !response.body)
      throw new Error(`Download failed (${response.status}): ${url}`);
    const temporary = `${archive}.part`;
    await pipeline(
      Readable.fromWeb(response.body),
      createWriteStream(temporary),
    );
    if ((await checksum(temporary)) !== digest) {
      await rm(temporary);
      throw new Error(
        `${name}: archive SHA-256 mismatch; installation stopped.`,
      );
    }
    await rename(temporary, archive);
  }
  await mkdir(extractionRoot, { recursive: true });
  await run("tar", ["-xzf", archive, "-C", extractionRoot]);
  if (!(await exists(marker)))
    throw new Error(`${name}: extraction did not create ${destination}.`);
  console.log(`${name}: installed`);
}

async function setup() {
  await checkPrerequisites();
  await run("tar", ["--version"], root, true).catch(() => {
    throw new Error(
      "Install tar (included with current Windows/macOS; use your Linux package manager).",
    );
  });
  await installArchive(
    "keycloak-26.8.1",
    "https://github.com/keycloak/keycloak/releases/download/26.8.1/keycloak-26.8.1.tar.gz",
    "4e2740b181faa2ea623a1c2ecf00855935311e546046e2f857615a47ed24cbe8",
    keycloak,
    join(keycloak, "bin", windows ? "kc.bat" : "kc.sh"),
    join(local, "tools"),
  );
  await installArchive(
    "dynamodb-2.6.1",
    "https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_2025-04-14.tar.gz",
    "9a8e6c1b1d4f5c1030c00a5a7eaee1a9ab2b8f1bbde7b700d5505898a3948fff",
    dynamodb,
    join(dynamodb, "DynamoDBLocal.jar"),
    dynamodb,
  );
  console.log(
    "Building backend (Maven Wrapper downloads Maven automatically)…",
  );
  if (windows)
    await run(
      join(root, "backend", "mvnw.cmd"),
      ["-B", "-DskipTests", "package"],
      join(root, "backend"),
    );
  else
    await run(
      "sh",
      [join(root, "backend", "mvnw"), "-B", "-DskipTests", "package"],
      join(root, "backend"),
    );
  console.log("Installing and building frontend assets…");
  const npm = windows ? "npm.cmd" : "npm";
  await run(npm, ["ci", "--no-audit", "--no-fund"], join(root, "frontend"));
  await run(npm, ["run", "build"], join(root, "frontend"));
  console.log("\nSetup complete. Start with: node scripts/local.mjs start");
}

async function assertFreePort(name, port) {
  await new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once("error", () =>
      reject(
        new Error(
          `${name}: port ${port} is occupied. Stop the older service/Compose stack before starting. Nothing will be stopped automatically.`,
        ),
      ),
    );
    server.listen(port, "127.0.0.1", () => server.close(resolvePort));
  });
}

async function launch(name, command, args, cwd = root) {
  if (stopping) throw new Error("Startup cancelled.");
  const log = join(logs, `${name}.log`);
  const file = await open(log, "a");
  await file.write(`\n--- ${new Date().toISOString()} starting ${name} ---\n`);
  const [executable, parameters] = prepareCommand(command, args);
  const child = spawn(executable, parameters, {
    cwd,
    env: environment,
    detached: !windows,
    windowsHide: true,
    stdio: ["ignore", file.fd, file.fd],
  });
  children.push({ name, child, file });
  child.once("error", (error) => {
    console.error(`${name}: ${error.message}`);
    void shutdown(1);
  });
  child.once("exit", (code) => {
    if (!stopping) {
      console.error(`${name} stopped unexpectedly (${code}). See ${log}`);
      void shutdown(1);
    }
  });
  console.log(`${name}: starting; log ${log}`);
}

async function waitReady(name, url, accept = (response) => response.ok) {
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline && !stopping) {
    try {
      if (accept(await fetch(url, { signal: AbortSignal.timeout(3000) }))) {
        console.log(`${name}: ready`);
        return;
      }
    } catch {
      /* Retry while the service boots. */
    }
    await new Promise((resolveWait) => setTimeout(resolveWait, 500));
  }
  throw new Error(
    `${name} did not become ready. See ${join(logs, `${name}.log`)}`,
  );
}

function shutdown(code = 0) {
  if (stopping) return stopping;
  stopping = Promise.resolve().then(async () => {
    console.log("\nStopping local services; saved data is retained…");
    for (const { child, file } of [...children].reverse()) {
      if (child.pid && child.exitCode === null) {
        if (windows) {
          // Ctrl+C also reaches attached child consoles. Allow their shutdown hooks to finish.
          await Promise.race([
            new Promise((resolveExit) => child.once("exit", resolveExit)),
            new Promise((resolveWait) => setTimeout(resolveWait, 1000)),
          ]);
          if (child.exitCode === null)
            await run(
              "taskkill.exe",
              ["/pid", String(child.pid), "/T", "/F"],
              root,
              true,
            ).catch(() => {});
        } else {
          const exited = new Promise((resolveExit) =>
            child.once("exit", resolveExit),
          );
          try {
            process.kill(-child.pid, "SIGTERM");
          } catch {
            /* Already exited. */
          }
          await Promise.race([
            exited,
            new Promise((resolveWait) => setTimeout(resolveWait, 5000)),
          ]);
          if (child.exitCode === null) {
            try {
              process.kill(-child.pid, "SIGKILL");
            } catch {
              /* Already exited. */
            }
          }
        }
      }
      await file.close();
    }
    process.exitCode = code;
  });
  return stopping;
}

async function start() {
  await checkPrerequisites();
  for (const artifact of [
    backendJar,
    join(root, "frontend", "dist", "index.html"),
    join(keycloak, "bin", windows ? "kc.bat" : "kc.sh"),
    join(dynamodb, "DynamoDBLocal.jar"),
  ]) {
    if (!(await exists(artifact)))
      throw new Error(
        `Missing ${artifact}. Run node scripts/local.mjs setup first.`,
      );
  }
  for (const [name, port] of Object.entries(ports))
    await assertFreePort(name, port);
  await mkdir(logs, { recursive: true });
  await mkdir(join(data, "dynamodb"), { recursive: true });
  await mkdir(join(data, "keycloak"), { recursive: true });
  await mkdir(join(keycloak, "data", "import"), { recursive: true });
  let realm = await readFile(
    join(root, "infra", "local", "realm.json"),
    "utf8",
  );
  realm = realm
    .replaceAll("http://localhost:3000", urls.customer)
    .replaceAll("http://localhost:3001", urls.admin);
  await writeFile(
    join(keycloak, "data", "import", "sandbox-realm.json"),
    realm,
  );
  process.once("SIGINT", () => {
    void shutdown();
  });
  process.once("SIGTERM", () => {
    void shutdown();
  });
  await launch("dynamodb", java, [
    `-Djava.library.path=${join(dynamodb, "DynamoDBLocal_lib")}`,
    "-jar",
    join(dynamodb, "DynamoDBLocal.jar"),
    "-sharedDb",
    "-dbPath",
    join(data, "dynamodb"),
    "-port",
    String(ports.dynamodb),
    "-disableTelemetry",
  ]);
  const kcArgs = [
    "start-dev",
    "--import-realm",
    "--http-host=127.0.0.1",
    "--http-management-host=127.0.0.1",
    `--http-port=${ports.keycloak}`,
    `--http-management-port=${ports.management}`,
    `--hostname=${urls.keycloak}`,
  ];
  if (windows)
    await launch("keycloak", join(keycloak, "bin", "kc.bat"), kcArgs, keycloak);
  else
    await launch(
      "keycloak",
      "sh",
      [join(keycloak, "bin", "kc.sh"), ...kcArgs],
      keycloak,
    );
  await waitReady(
    "dynamodb",
    urls.dynamodb,
    (response) => response.status === 400,
  );
  await waitReady(
    "keycloak",
    `${urls.keycloak}/realms/sandbox/.well-known/openid-configuration`,
  );
  await launch("backend", java, [
    "-jar",
    backendJar,
    "--spring.profiles.active=local",
    "--server.address=127.0.0.1",
    `--server.port=${ports.backend}`,
  ]);
  await waitReady("backend", `${urls.backend}/actuator/health`);
  for (const name of ["customer", "admin"]) {
    await launch(name, process.execPath, [
      join(root, "scripts", "serve.mjs"),
      name,
      String(ports[name]),
    ]);
    await waitReady(name, urls[name]);
  }
  console.log(
    `\nCustomer: ${urls.customer} (customer / customer-password)\nAdministrator: ${urls.admin} (admin / admin-password)\nKeycloak: ${urls.keycloak}/admin (local-admin / local-admin-password)\nBackend: ${urls.backend}/actuator/health\n\nKeep this terminal open. Press Ctrl+C to stop all five services. Data: ${data}`,
  );
  if (process.argv.includes("--smoke")) await shutdown();
}

try {
  const command = process.argv[2];
  if (command === "setup") await setup();
  else if (command === "start") await start();
  else if (command === "check") {
    await checkPrerequisites();
    for (const [name, port] of Object.entries(ports))
      await assertFreePort(name, port);
    console.log("Prerequisites and service ports are ready.");
  } else
    throw new Error(
      "Usage: node scripts/local.mjs setup | start [--smoke] | check",
    );
} catch (error) {
  console.error(`\nLocal startup: ${error.message}`);
  if (children.length) await shutdown(1);
  else process.exitCode = 1;
}
