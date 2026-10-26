# More Nginx exercises: choose one and run it

This guide covers WebSockets, server-sent events (SSE), uploads, Basic authentication, virtual hosts, and TCP balancing. All source code and configuration are included. Complete the common setup once; then pick any exercise. The TCP exercise has its own additional module/setup step.

## Common setup

### Requirements

These commands target **Linux**, with Nginx and Java running on the same host. Use Java 21, Nginx, OpenSSL, and curl. On Ubuntu/Debian, install missing tools with:

```bash
sudo apt update
sudo apt install openjdk-21-jdk nginx openssl curl
```

The package may start a system Nginx on port 80. The lab uses its own PID/config and ports 8090–8092. You do not need to change or stop that system service.

Run all subsequent shell commands from the **project root**, the directory containing `gradlew`. Keep the Nginx commands unprivileged; do not start the local lab with `sudo`.

Check Java and build the app:

```bash
java -version
./gradlew bootJar
```

The build downloads the Spring WebSocket starter the first time, so internet access to dependency repositories is needed. If Java 21 is installed but another version is selected, set `JAVA_HOME` to your Java 21 installation and add its `bin` directory to `PATH` before building/running.

Stop any old Java processes started from the earlier guide with Ctrl+C. Rebuilds do not update already-running instances. Open three terminals and run one command in each:

```bash
# Terminal 1
java -jar build/libs/NginxPractice-0.0.1-SNAPSHOT.jar --server.port=8081 --server.address=127.0.0.1
```

```bash
# Terminal 2
java -jar build/libs/NginxPractice-0.0.1-SNAPSHOT.jar --server.port=8082 --server.address=127.0.0.1
```

```bash
# Terminal 3
java -jar build/libs/NginxPractice-0.0.1-SNAPSHOT.jar --server.port=8083 --server.address=127.0.0.1
```

In a fourth terminal, check all three:

```bash
curl -f http://127.0.0.1:8081/api/server1
curl -f http://127.0.0.1:8082/api/server1
curl -f http://127.0.0.1:8083/api/server1
```

Each must return JSON with its own `instancePort`. The endpoints' names do not determine the instance; all three endpoints exist on every instance.

### Prepare and start the HTTP lab

```bash
mkdir -p examples/advanced/logs examples/advanced/temp/client examples/advanced/temp/proxy
```

Generate a password file for the Basic auth exercise. This is required once even if you choose a different exercise, because the shared config references it. The username is `learner`; choose your own password at the OpenSSL prompt:

```bash
printf 'learner:%s\n' "$(openssl passwd -apr1)" > examples/advanced/credentials.htpasswd
chmod 600 examples/advanced/credentials.htpasswd
```

Do not rerun this command unless you want to replace the lab password. The password hash and runtime directories are ignored by Git. The MD5-based hash here is a portable Nginx lab format; choose stronger credential storage/identity integration for a deployed application.

Validate the provided configuration:

```bash
nginx -p "$PWD/examples/advanced/" -c nginx.conf -t
```

Only after it succeeds, start the lab:

```bash
nginx -p "$PWD/examples/advanced/" -c nginx.conf
```

Open **http://localhost:8090/**. The page has controls for WebSocket echo, SSE, file upload, and a protected API link. Serve it through Nginx; do not double-click the HTML file on disk.

| Address | Purpose |
| --- | --- |
| `localhost:8090` | Browser lab and HTTP API |
| `app1.test:8091` | Virtual host routed to 8081 |
| `app2.test:8091` | Virtual host routed to 8082 |
| `127.0.0.1:8092` | Optional separate TCP lab |

The included config is [nginx.conf](nginx.conf), the browser page is [index.html](html/index.html), and Java examples are [LabController](../../src/main/java/com/example/NginxPractice/LabController.java) and [WebSocketConfig](../../src/main/java/com/example/NginxPractice/WebSocketConfig.java).

## 1. WebSocket echo

**Use it for:** chat, live collaboration, or bidirectional updates. A WebSocket keeps a connection open so either side can send messages.

**Try it:** open the browser lab, enter `Hello Nginx`, and click **Connect and send**. Expect a reply like:

```text
Port 8082 echoed: Hello Nginx
```

The page closes the connection after the reply so you can repeat the test. Each new connection may choose a different backend; messages within one connection stay on that connection's backend.

**How it works:** `WebSocketConfig` registers `/ws/echo` with a `TextWebSocketHandler`. The Nginx `/ws/` location uses HTTP/1.1 and forwards `Upgrade` and `Connection` headers. A successful handshake returns **101 Switching Protocols**, visible in the browser's Network → WS panel. The message appears under that connection's messages/frames.

The proxy preserves `Host`, including the port, so Spring's default same-origin WebSocket check accepts a page opened at `localhost:8090`. The application does not allow all cross-origin clients. If you change the hostname or add a frontend on another origin, configure the intended origins deliberately.

A WebSocket connection will not migrate to another Java instance if its backend stops. The client needs to reconnect. This demo echoes text; it does not persist messages or broadcast between instances.

References: [Spring WebSocket API](https://docs.spring.io/spring-framework/reference/web/websocket/server.html), [Nginx WebSocket proxying](https://nginx.org/en/docs/http/websocket.html).

## 2. Server-sent events (SSE)

**Use it for:** progress notifications or live status feeds where the server sends updates to a browser over HTTP. Unlike WebSockets, SSE is one-way.

**Try it:** click **Receive five events** on the browser page. Expect one line per second, ending after event 5. Or use:

```bash
curl -N --max-time 15 http://localhost:8090/api/events
```

Expected shape:

```text
id:1
event:tick
data:Event 1 from port 8081

...
id:5
event:tick
data:Event 5 from port 8081
```

**How it works:** `LabController.events()` uses Spring's `SseEmitter`. A short-lived Java virtual thread sends five events and completes the stream. The Nginx exact-match location disables response buffering and caching so events reach the client as they arrive. `curl -N` also disables curl's output buffering.

The browser client closes its `EventSource` after event 5. Without that explicit close, a browser normally reconnects after a stream ends. This demo starts a new sequence on each request; it does not implement replay from `Last-Event-ID`. Production feeds need disconnect handling, connection limits, heartbeats, and a replay policy appropriate to their data.

Reference: [Spring MVC asynchronous requests](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html).

## 3. File uploads and size limits

**Use it for:** receiving multipart files while rejecting oversized requests at the edge and in Java.

**Try it:** choose a small, non-empty file on the browser page and click **Upload**. Or create a test file and send it:

```bash
printf 'Hello Nginx\n' > examples/advanced/temp/demo.txt
curl -i -F 'file=@examples/advanced/temp/demo.txt' http://localhost:8090/api/upload
```

Expect **200**, with fields similar to:

```json
{"filename":"demo.txt","size":12,"instancePort":"8082","stored":false}
```

The endpoint reports metadata. It **does not save file contents**. Multipart parsing may use temporary disk storage, which the servlet container cleans up after the request. Client filenames are never used to create server paths.

**Understand the two limits:**

| Layer | Config | Limit |
| --- | --- | --- |
| Nginx | `client_max_body_size 2m` on `/api/upload` | Entire HTTP request body: 2 MiB |
| Spring | `spring.servlet.multipart.max-file-size: 1MB` | Each file: 1 MiB |
| Spring | `spring.servlet.multipart.max-request-size: 2MB` | Entire multipart request: 2 MiB |

The request limit leaves room for multipart boundary/header overhead around a valid 1 MiB file. Try the rejection cases:

```bash
# Empty file: Java returns 400.
touch examples/advanced/temp/empty.txt
curl -i -F 'file=@examples/advanced/temp/empty.txt' http://localhost:8090/api/upload

# 1.5 MiB file: under Nginx's total limit, over Spring's file limit → 413.
dd if=/dev/zero of=examples/advanced/temp/too-large-for-java.bin bs=1024 count=1536
curl -i -H 'Expect:' -F 'file=@examples/advanced/temp/too-large-for-java.bin' http://localhost:8090/api/upload

# 3 MiB file: Nginx rejects the request → 413, before the controller runs.
dd if=/dev/zero of=examples/advanced/temp/too-large-for-nginx.bin bs=1024 count=3072
curl -i -H 'Expect:' -F 'file=@examples/advanced/temp/too-large-for-nginx.bin' http://localhost:8090/api/upload
```

The body of a 413 can differ: Nginx usually returns HTML; Spring handles its own multipart exception response. An upload product also needs storage, authorization, content checks, and a retention policy; metadata inspection here demonstrates the proxy flow without creating permanent files.

References: [Spring multipart properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html), [Nginx request body size](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_max_body_size).

## 4. Basic authentication at the gateway

**Use it for:** a simple password gate around an internal demo or admin tool. Here Nginx validates credentials before forwarding the request.

**Try it without credentials:**

```bash
curl -i http://localhost:8090/private/api/server1
```

Expect **401 Unauthorized** and a `WWW-Authenticate` header.

**Try it with credentials:**

```bash
curl -i -u learner http://localhost:8090/private/api/server1
```

Curl prompts for the password you chose during setup. Expect **200** and JSON from one Java instance. A wrong password returns 401. In the browser, follow **Open protected API** and enter the same credentials; browsers may remember them for the session.

**How it works:** the `/private/` location uses `auth_basic` and the generated password file. Its `proxy_pass` has a trailing slash, turning `/private/api/server1` into `/api/server1` for Java.

Only `/private/` is protected. The original `/api/server1` remains public for the other experiments. **This demonstrates a gateway password check, not whole-app security.** Java has no user authorization, and direct backend access bypasses Nginx. Keep the lab bound to loopback; use HTTPS for real Basic auth because the Authorization header is encoded, not encrypted. Do not expose the TCP lab as a second public route around an authentication gateway.

We use Basic auth for this runnable exercise. Authentication subrequests (`auth_request`) are a separate design: Nginx asks an identity service whether to allow the original request. They require a real identity/session contract and are not simulated here.

Reference: [Nginx Basic authentication](https://nginx.org/en/docs/http/ngx_http_auth_basic_module.html).

## 5. Two domains on one Nginx port

**Use it for:** hosting several applications on one server IP. Nginx selects the matching HTTP `Host` using `server_name`.

**Try it without editing DNS or `/etc/hosts`:**

```bash
curl --noproxy '*' --resolve app1.test:8091:127.0.0.1 http://app1.test:8091/api/server1
curl --noproxy '*' --resolve app2.test:8091:127.0.0.1 http://app2.test:8091/api/server1
```

The first returns `instancePort: 8081`; the second returns 8082. Both request the same path on the same IP and port. `--resolve` tells curl where to connect while preserving the hostname in the request. `--noproxy` prevents proxy environment variables from sending the local test elsewhere.

Unknown hostnames use the explicit default server and return **404**:

```bash
curl -i http://127.0.0.1:8091/api/server1
```

For a browser test, optionally add the following line to `/etc/hosts` using your editor with administrator rights, then visit each named URL:

```text
127.0.0.1 app1.test app2.test
```

Remove that line when finished. `.test` is used for the local exercise. For actual internet domains, configure DNS and certificates for each name. The HTTP virtual-host test does not require TLS or a public domain.

Reference: [Nginx request processing and server selection](https://nginx.org/en/docs/http/request_processing.html).

## 6. TCP load balancing (Layer 4)

**Use it for:** forwarding TCP connections without interpreting HTTP URLs, such as some database or custom TCP protocols. This exercise reuses Spring's HTTP servers as TCP destinations, so no extra echo server is needed. Curl still sends HTTP, but the Nginx `stream` layer only forwards bytes.

This is a **separate Nginx process**, with its own PID/log/config, listening on **8092**. You can run it independently or alongside the HTTP lab. It still needs the three Java instances from common setup.

### Install the stream module

On Ubuntu/Debian:

```bash
sudo apt install libnginx-mod-stream
ls /usr/lib/nginx/modules/ngx_stream_module.so
```

The provided [nginx-stream.conf](nginx-stream.conf) explicitly loads that file because this isolated configuration does not read the system module includes.

For other installations, inspect `nginx -V`. With a statically compiled `--with-stream` build, remove the `load_module` line. With a dynamic `--with-stream=dynamic` build, install the matching module package and adjust the path if needed. A dynamic build flag alone does not mean the module file is installed.

### Start and test

```bash
mkdir -p examples/advanced/logs
nginx -p "$PWD/examples/advanced/" -c nginx-stream.conf -t
```

Only if validation succeeds:

```bash
nginx -p "$PWD/examples/advanced/" -c nginx-stream.conf
```

Open a new connection for each request:

```bash
for i in $(seq 1 9); do
    curl -sS -H 'Connection: close' http://127.0.0.1:8092/api/server1
    printf '\n'
done
```

Expect multiple `instancePort` values. Selection is per **TCP connection**, so requests reusing one connection stay on the same backend. Unlike HTTP proxying, this layer cannot select a backend using `/api/server1`, insert HTTP forwarding headers, or apply the `/private/` Basic auth rule.

Stop the stream lab:

```bash
nginx -p "$PWD/examples/advanced/" -c nginx-stream.conf -s quit
```

References: [stream core module](https://nginx.org/en/docs/stream/ngx_stream_core_module.html), [TCP proxy module](https://nginx.org/en/docs/stream/ngx_stream_proxy_module.html).

## Reload, logs, and cleanup

After editing the HTTP config, validate it; then reload only if validation succeeds:

```bash
nginx -p "$PWD/examples/advanced/" -c nginx.conf -t
nginx -p "$PWD/examples/advanced/" -c nginx.conf -s reload
```

For TCP config edits, use `-c nginx-stream.conf` instead. Runtime files are under `examples/advanced/logs`:

```bash
tail -f examples/advanced/logs/access.log examples/advanced/logs/error.log
```

| Problem | Fix/check |
| --- | --- |
| 502 | Confirm all three rebuilt Java instances are running on the expected ports |
| New endpoint returns 404 | Stop the old JAR processes, rebuild, restart all three |
| WebSocket fails | Open the page through Nginx; check Network → WS and upgrade headers |
| SSE appears all at once | Use `curl -N`; confirm the exact SSE location has buffering off |
| Authentication returns 500 | Check password-file existence, permissions, and error log |
| `dlopen()` fails | Install the stream module matching your Nginx package/version |
| `unknown directive "stream"` | Load/install the module, or use a build containing it |
| Address already in use | Lab may already be running; use reload, or stop the process owning the port |
| Permission denied on log/PID | Start the lab as the same user who owns its runtime directories |
| Custom domain fails | Use the exact `--resolve` command above before changing DNS |

Stop the HTTP lab, then stop Java with Ctrl+C in its three terminals:

```bash
nginx -p "$PWD/examples/advanced/" -c nginx.conf -s quit
```

Quit the stream process separately if started. Generated temp files can then be removed from `examples/advanced/temp`; password files can be deleted if no longer needed. Never delete PID/log directories while their Nginx process is running.

## Automated checks and what they cover

```bash
./gradlew test
```

Tests cover upload metadata, empty/missing-file rejection, five-event stream completion, and the registered WebSocket echo handler. MockMvc tests do not start Nginx or exercise servlet multipart size enforcement. Use the curl/browser checks above for upgrade handshakes, streaming timing, gateway authentication, virtual hosts, both upload limits, and TCP routing.

Validation in the authoring environment: Java 21 compilation and all five project tests passed with a direct compiler/JUnit runner. Gradle could not initialize its network-based lock service here, so a complete Gradle build and the new starter's dependency resolution remain unverified. Browser JavaScript syntax passed. Nginx accepted the HTTP configuration's syntax, then its `-t` check was blocked by sandbox socket restrictions. The stream module is absent here. The real browser/curl/TCP flows still need to be run on your machine using the steps above.
