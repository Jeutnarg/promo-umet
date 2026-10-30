import http from "node:http";
const upstreams = {
  3000: ["customer", 80],
  3001: ["admin", 80],
  8080: ["keycloak", 8080],
  8081: ["backend", 8081],
};
for (const [port, [host, targetPort]] of Object.entries(upstreams)) {
  http
    .createServer((request, response) => {
      const proxy = http.request(
        {
          host,
          port: targetPort,
          path: request.url,
          method: request.method,
          headers: request.headers,
        },
        (upstream) => {
          response.writeHead(upstream.statusCode, upstream.headers);
          upstream.pipe(response);
        },
      );
      proxy.on("error", () => {
        response.writeHead(502);
        response.end("Local upstream is not ready");
      });
      request.pipe(proxy);
    })
    .listen(Number(port), "127.0.0.1");
}
