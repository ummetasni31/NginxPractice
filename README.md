# Learning Nginx with this Spring Boot project

This project is a small lab for learning how Nginx sits in front of an application. Start with one reverse proxy, run three copies of the app, and then try the experiments below one at a time.

## 1. Understand the application first

The project uses Java 21, Gradle, and Spring Boot. Its default port is **8081**, configured in `src/main/resources/application.yml`.

`src/main/java/com/example/NginxPractice/ServerController.java` exposes:

| Request | Response field `endpoint` | Response field `message` |
| --- | --- | --- |
| `GET /api/server1` | `server1` | `Response from Server 1` |
| `GET /api/server2` | `server2` | `Response from Server 2` |
| `GET /api/server3` | `server3` | `Response from Server 3` |

Every response also contains `instancePort`. For example, when port 8082 handles `/api/server1`:

```json
{"endpoint":"server1","instancePort":"8082","message":"Response from Server 1"}
```

**Endpoint names and running instances are different things.** All three endpoints exist on every instance. For load balancing, repeatedly request the **same endpoint** and watch `instancePort` change.

The project also includes runnable WebSocket, SSE, and upload demos plus a small browser test page. See [section 16](#16-more-runnable-nginx-exercises) for those exercises. The original three server endpoints still behave the same way. There is no database or Actuator health endpoint.

## 2. What can Nginx do here?

| Role | What you learn | Example in this lab |
| --- | --- | --- |
| Reverse proxy | Expose one entry point for a backend | `8080 → 8081` |
| API gateway routing | Choose a backend using the request path | `/api/server2 → 8082` |
| Load balancer | Distribute requests among equivalent instances | `/api/server1 → 8081 / 8082 / 8083` |
| Failover | Continue serving when an instance stops | Stop 8082 and observe remaining instances |
| Static file server | Serve files without calling Java | `/ → index.html` |
| Response cache | Reuse a public response temporarily | Repeated GETs show `MISS`, then `HIT` |
| Rate limiter | Restrict request frequency | Excess requests receive HTTP 429 |
| TLS terminator | Accept HTTPS before forwarding to Java | `HTTPS → Nginx → HTTP backend` |
| Compression layer | Reduce larger text responses | Compress JSON or frontend assets |
| Request logging | Record timings and backend selection | Log `$upstream_addr` |
| Deployment router | Move traffic between application versions | Switch the upstream pool |

Nginx can combine these roles. Calling it a gateway does not automatically add user authentication, authorization, or business logic; those still need an explicit design.

## 3. Start three app instances

Run these commands from the project root on a Linux machine with Java 21 available:

```bash
./gradlew bootJar
```

Open three terminals. Run one command in each:

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

Use the executable JAR, not the `-plain.jar`. If the project version changes, update the filename. Binding to loopback keeps this host-based lab's backend ports local.

Verify the instances directly:

```bash
curl -sS http://127.0.0.1:8081/api/server1
curl -sS http://127.0.0.1:8082/api/server1
curl -sS http://127.0.0.1:8083/api/server1
```

Expect the same endpoint and different `instancePort` values. Stop an instance with Ctrl+C in its terminal.

## 4. Create an isolated Nginx lab

The examples assume Nginx is installed on the **same host** as Java. Check with `nginx -v`. Installation commands depend on your operating system.

Use port **8080** and a local config so you can practice without editing `/etc/nginx/nginx.conf`. The lab listens only on loopback.

```bash
mkdir -p nginx-lab/logs nginx-lab/cache nginx-lab/html
```

Create `nginx-lab/nginx.conf` with this **complete configuration**:

```nginx
worker_processes 1;
pid logs/nginx.pid;
error_log logs/error.log warn;

events {
    worker_connections 1024;
}

http {
    client_body_temp_path temp/client;
    proxy_temp_path temp/proxy;

    log_format lab '$remote_addr "$request" status=$status '
                   'upstream=$upstream_addr upstream_status=$upstream_status '
                   'request_time=$request_time upstream_time=$upstream_response_time';
    access_log logs/access.log lab;

    # These settings are inherited by locations that do not override them.
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection "";

    upstream spring_pool {
        server 127.0.0.1:8081;
        server 127.0.0.1:8082;
        server 127.0.0.1:8083;
    }

    server {
        listen 127.0.0.1:8080;
        server_name localhost;

        location /api/ {
            proxy_pass http://spring_pool;
        }

        location / {
            return 404;
        }
    }
}
```

Prepare temporary directories, validate the configuration, then start it:

```bash
mkdir -p nginx-lab/temp/client nginx-lab/temp/proxy
nginx -p "$PWD/nginx-lab/" -c nginx.conf -t
nginx -p "$PWD/nginx-lab/" -c nginx.conf
```

Run these commands from the project root every time so `$PWD` points to the same directory. Relative log, cache, and PID paths use the supplied prefix.

After each edit, **validate before reloading**:

```bash
nginx -p "$PWD/nginx-lab/" -c nginx.conf -t
# Run the next command only if validation succeeds.
nginx -p "$PWD/nginx-lab/" -c nginx.conf -s reload
```

Stop this lab gracefully:

```bash
nginx -p "$PWD/nginx-lab/" -c nginx.conf -s quit
```

Keep using this prefix when controlling Nginx; a plain system reload might target another installation. Configuration blocks have specific contexts: `upstream`, `log_format`, and cache/rate-limit zone definitions go inside `http`; `location` goes inside `server`.

Reference: [Nginx beginner's guide](https://nginx.org/en/docs/beginners_guide.html).

## 5. Reverse proxy: one public entry point

For a single-backend experiment, replace the `/api/` location with:

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8081;
}
```

Reload, then request:

```bash
curl -i http://127.0.0.1:8080/api/server1
```

The client uses 8080; Java answers from 8081. You can later change the backend address without changing the client URL.

### The trailing slash matters

Inside `location /api/`, these have different meanings:

| Setting | Backend receives for `/api/server1` |
| --- | --- |
| `proxy_pass http://spring_pool;` | `/api/server1` |
| `proxy_pass http://spring_pool/;` | `/server1` |

Use the first form for this controller. The second replaces the matched `/api/` prefix and produces a backend 404 here.

Reference: [proxy_pass URI handling](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass).

## 6. API gateway: route by path

Replace the original `/api/` location with these four locations inside the existing server:

```nginx
location = /api/server1 {
    proxy_pass http://127.0.0.1:8081;
}

location = /api/server2 {
    proxy_pass http://127.0.0.1:8082;
}

location = /api/server3 {
    proxy_pass http://127.0.0.1:8083;
}

location /api/ {
    return 404;
}
```

Test each through port 8080. The first should return `instancePort: 8081`, the second 8082, and the third 8083. The `=` locations match exact paths; `/api/server1/` is a different path.

This simulates routing to different services, but the three processes still run the same app. In a real system, `/orders/` and `/inventory/` might each select a different service pool. Each pool can independently load balance multiple instances.

## 7. Load balancing: one path, several instances

Restore the original `/api/` location using `proxy_pass http://spring_pool;`. Remove the three exact-match locations from the routing experiment; otherwise they will continue taking priority.

```text
Client → Nginx :8080 → Spring :8081
                    → Spring :8082
                    → Spring :8083
```

Send repeated requests:

```bash
for i in $(seq 1 12); do
    curl -sS http://127.0.0.1:8080/api/server1
    printf '\n'
done
```

Expect all three port values over multiple requests. Do not build assertions around an exact rotation order: failures and concurrent traffic can affect selection.

Try one upstream configuration at a time:

### Round robin

The original `spring_pool` uses the default method. Equivalent instances share requests.

### Weighted distribution

```nginx
upstream spring_pool {
    server 127.0.0.1:8081 weight=3;
    server 127.0.0.1:8082 weight=1;
    server 127.0.0.1:8083 weight=1;
}
```

Over enough requests, 8081 should handle roughly three fifths of traffic when all instances are available. Weights are relative shares, not a percentage guarantee for a small sample.

### Least connections

```nginx
upstream spring_pool {
    least_conn;
    server 127.0.0.1:8081;
    server 127.0.0.1:8082;
    server 127.0.0.1:8083;
}
```

Useful when requests have different durations. These tiny JSON endpoints finish quickly, so sequential curls do not demonstrate its advantage well.

### Client IP affinity

```nginx
upstream spring_pool {
    ip_hash;
    server 127.0.0.1:8081;
    server 127.0.0.1:8082;
    server 127.0.0.1:8083;
}
```

Repeated calls from the same client IP tend to reach the same instance. Local curls therefore will not show broad distribution. Many users behind one NAT can share an IP. Affinity does not replicate session data or protect it when an instance fails.

References: [HTTP load balancing](https://nginx.org/en/docs/http/load_balancing.html), [upstream configuration](https://nginx.org/en/docs/http/ngx_http_upstream_module.html).

## 8. Failover when an instance stops

Use this pool with the load-balanced location:

```nginx
upstream spring_pool {
    server 127.0.0.1:8081 max_fails=1 fail_timeout=10s;
    server 127.0.0.1:8082 max_fails=1 fail_timeout=10s;
    server 127.0.0.1:8083 max_fails=1 fail_timeout=10s;
}
```

Add these directives to the existing `/api/` location:

```nginx
proxy_connect_timeout 1s;
proxy_next_upstream error timeout;
proxy_next_upstream_tries 3;
```

Reload, stop the 8082 terminal, and repeat the curl loop. Requests should continue through the other instances. Restart 8082, wait beyond the failure interval, and send more requests.

This is **passive failure detection**: real requests expose connection failures. It does not periodically inspect a health URL. A reachable process returning a business error is a different situation. Do not add a `health_check` directive assuming it is available in every Nginx edition/build.

For a standby experiment, replace the pool with:

```nginx
upstream spring_pool {
    server 127.0.0.1:8081;
    server 127.0.0.1:8082;
    server 127.0.0.1:8083 backup;
}
```

8083 receives traffic when the primary servers are unavailable. Try this with round robin, not `ip_hash`.

Retries are appropriate for this GET lab. For future payment/order writes, design idempotency before changing retry behavior; a connection failure does not prove a backend performed no write. A response already partly sent cannot simply be replaced with another backend's response.

Reference: [upstream failure and backup parameters](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#server).

## 9. Serve a static page and the API together

Create a page:

```bash
cat > nginx-lab/html/index.html <<'HTML'
<!doctype html>
<html lang="en">
<meta charset="utf-8">
<title>Nginx Practice</title>
<h1>Nginx Practice</h1>
<p><a href="/api/server1">Call the Spring API</a></p>
</html>
HTML
```

Replace the server's `location /` block:

```nginx
location / {
    root html;
    index index.html;
    try_files $uri $uri/ =404;
}
```

Keep the existing `/api/` proxy location. Open `http://127.0.0.1:8080/`, then follow the API link. Nginx serves the page; Java handles the API.

For a future Angular/React SPA, a fallback such as `try_files $uri $uri/ /index.html;` supports browser routes. Keep API paths separate so an unknown API URL does not return the frontend HTML. A real asset directory also needs appropriate MIME types; use the `mime.types` supplied by your Nginx installation.

Serving frontend and API under one origin often removes the need for browser CORS configuration. Different ports are different origins.

Reference: [core directives](https://nginx.org/en/docs/http/ngx_http_core_module.html).

## 10. Cache a public GET response

This experiment deliberately reuses an old `instancePort` response. **Disable caching again before testing load balancing.**

Add inside `http`, outside `server`:

```nginx
proxy_cache_path cache levels=1:2 keys_zone=lab_cache:10m max_size=50m inactive=1m;
```

Add to the existing `/api/` location:

```nginx
proxy_cache lab_cache;
proxy_cache_valid 200 10s;
add_header X-Cache-Status $upstream_cache_status always;
```

Reload and call the same URL twice:

```bash
curl -i http://127.0.0.1:8080/api/server1
curl -i http://127.0.0.1:8080/api/server1
```

Expect `X-Cache-Status: MISS`, followed by `HIT` within the cache window. A cache hit does not invoke the controller; compare the application logs. After expiry, a new backend response can change the displayed port.

Use this example only for the current public, stateless demo. Authenticated, tenant-specific, cookie-dependent, and personal responses need explicit cache bypass rules and a correct cache key. Do not force caching by ignoring privacy headers.

Reference: [proxy cache directives](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_cache).

## 11. Limit request rate

Remove the cache directives from `/api/` for this experiment. Define a shared zone inside `http`:

```nginx
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=2r/s;
```

Add to `/api/`:

```nginx
limit_req zone=api_limit burst=3 nodelay;
limit_req_status 429;
```

Reload and generate a short burst:

```bash
seq 1 20 | xargs -P 10 -I '{}' curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/server1
```

Expect some 200s and some 429s. The zone allows an average rate with a small burst; it is not a strict two-requests-per-calendar-second counter. Remove the location directives when finished.

This keys on the source IP seen by Nginx. Behind another trusted proxy, configure real client IP handling first. Otherwise unrelated users may share the proxy's limit. Do not trust arbitrary client-supplied forwarding headers.

Reference: [request rate limiting](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html).

## 12. HTTPS termination

This optional local experiment requires OpenSSL and an Nginx build with TLS support. Generate a **local development** certificate:

```bash
mkdir -p nginx-lab/certs
openssl req -x509 -nodes -newkey rsa:2048 -days 7 \
    -keyout nginx-lab/certs/localhost.key \
    -out nginx-lab/certs/localhost.crt \
    -subj '/CN=localhost' \
    -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
chmod 600 nginx-lab/certs/localhost.key
```

Add a **second** server inside `http`:

```nginx
server {
    listen 127.0.0.1:8443 ssl;
    server_name localhost;
    ssl_certificate certs/localhost.crt;
    ssl_certificate_key certs/localhost.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    location /api/ {
        proxy_pass http://spring_pool;
    }

    location / {
        return 404;
    }
}
```

Reload and test while explicitly trusting that certificate:

```bash
curl --cacert nginx-lab/certs/localhost.crt https://localhost:8443/api/server1
```

The browser/client uses HTTPS, while Nginx connects to the local backend over HTTP. For a real domain, use a publicly trusted certificate with automated renewal. Cross-host backend traffic may also need TLS. Keep private keys out of source control.

The base lab overwrites forwarding headers because Nginx is the first trusted edge. If you later put another proxy in front, define that trust chain explicitly. Spring also needs an appropriate forwarded-header strategy before relying on those headers for generated URLs or secure redirects; this controller currently only returns JSON.

Reference: [configuring HTTPS servers](https://nginx.org/en/docs/http/configuring_https_servers.html).

## 13. Compression

Add inside `http`:

```nginx
gzip on;
gzip_vary on;
gzip_min_length 256;
gzip_types application/json text/plain text/css application/javascript;
```

Test with:

```bash
curl -H 'Accept-Encoding: gzip' -D - -o /dev/null http://127.0.0.1:8080/api/server1
```

The current JSON is smaller than 256 bytes, so it may remain uncompressed. To demonstrate compression on these tiny responses, temporarily set `gzip_min_length 1` and test again. In practice, prioritize larger JSON and text assets. Avoid casually compressing responses mixing secrets and attacker-controlled content over TLS.

Reference: [gzip module](https://nginx.org/en/docs/http/ngx_http_gzip_module.html).

## 14. Logs and troubleshooting

Watch the files defined in the base config:

```bash
tail -f nginx-lab/logs/access.log nginx-lab/logs/error.log
```

`upstream` identifies the selected backend. `request_time` measures overall request processing; `upstream_time` records upstream response timing. Multiple upstream values can indicate retries. Cached/static responses may have no upstream address.

| Symptom | First checks |
| --- | --- |
| Connection refused on 8080 | Is the lab Nginx running? Did startup fail? |
| `bind() ... address already in use` | Another process owns that port; choose a free port |
| 502 Bad Gateway | Curl each backend directly; check ports and error log |
| 504 Gateway Timeout | Backend is too slow/unresponsive; inspect it before raising timeouts |
| Backend 404 | Check exact controller path and `proxy_pass` trailing slash |
| Same port repeatedly | Check cache, `ip_hash`, exact locations, and unavailable instances |
| 429 Too Many Requests | Remove or adjust the rate-limit experiment |
| Permission denied | Check lab directory ownership and Nginx worker access |
| `directive is not allowed here` | Check whether the directive belongs in `http`, `server`, or `location` |
| Unknown directive | Check `nginx -V`; the installed build may lack that module |
| Config edit has no effect | Validate and reload the same prefix/config that you started |

Useful read-only commands:

```bash
nginx -V
nginx -p "$PWD/nginx-lab/" -c nginx.conf -T
```

`-T` prints the effective configuration; avoid sharing its output if your future config contains secrets. Logs can also contain sensitive URL/query data.

Reference: [access log module](https://nginx.org/en/docs/http/ngx_http_log_module.html).

## 15. Deployment routing: blue/green

For a deployment exercise, treat 8081 and 8082 as the current release and 8083 as the candidate. Initially configure only 8081 and 8082 in `spring_pool`.

Check the candidate directly:

```bash
curl -f http://127.0.0.1:8083/api/server1
```

Then replace the pool with only 8083, validate, and reload. New traffic uses the candidate after the reload transition. Restore the previous pool and reload to roll back routing.

Existing requests can finish on old workers/backends. Keep the previous app instances alive until they drain. This lab runs the same JAR everywhere; a real blue/green exercise needs separate application builds and compatibility checks for shared databases and sessions.

Weighted balancing can simulate a small canary share, but Nginx weights alone do not assess whether the new version is healthy.

## 16. More runnable Nginx exercises

These examples now include application code, complete Nginx configuration files, and a browser test page. Follow the [step-by-step advanced lab guide](examples/advanced/README.md). Its setup is independent of sections 4–15; you do not need to merge snippets into the earlier configuration.

| Pick an exercise | Included implementation | Test entry point |
| --- | --- | --- |
| [WebSockets](examples/advanced/README.md#1-websocket-echo) | `/ws/echo`, Nginx upgrade headers, browser client | `http://localhost:8090/` |
| [Server-sent events](examples/advanced/README.md#2-server-sent-events-sse) | `/api/events`, five timed events, buffering disabled | Browser or `curl -N` |
| [File uploads](examples/advanced/README.md#3-file-uploads-and-size-limits) | `/api/upload`, multipart limits, metadata response | Browser or `curl -F` |
| [Authentication gateway](examples/advanced/README.md#4-basic-authentication-at-the-gateway) | Basic auth on `/private/`, generated password file | Browser or `curl -u` |
| [Multiple domains](examples/advanced/README.md#5-two-domains-on-one-nginx-port) | `app1.test` and `app2.test` virtual hosts | `curl --resolve`, no DNS edits needed |
| [TCP load balancing](examples/advanced/README.md#6-tcp-load-balancing-layer-4) | Separate `stream` configuration using existing Java servers | Port 8092 |

If you later containerize the lab, `127.0.0.1` inside an Nginx container refers to that container. Replace these upstream addresses with reachable service names on a shared container network; the host-only commands above are not a Docker setup.

## 17. Suggested practice order and validation boundary

1. Start all three instances and verify their ports directly.
2. Start the base lab and observe load balancing on one URL.
3. Try single-backend proxying, then exact-path gateway routing.
4. Restore balancing and compare the selection methods.
5. Stop/restart an instance and inspect failover logs.
6. Try caching, then remove it and try rate limiting.
7. Serve the static page and add local HTTPS.
8. Practice deployment switching and rollback.

The Java source and build configuration were inspected when writing this guide. Java 21 compilation and all five JUnit tests passed using a direct compiler/JUnit runner because the environment prevented Gradle from initializing its network-based lock service. This does not verify the full Gradle build or newly added starter dependency resolution. Browser JavaScript syntax and Markdown code fences were also checked.

The installed Nginx accepted the base and advanced HTTP configurations' syntax, but full `-t` checks failed because this environment prohibits creating listening sockets (`Operation not permitted`). The stream module is not installed here, so the TCP config and end-to-end browser/curl experiments were not executed.

It is documentation, not an installed Nginx deployment. Follow each experiment's commands and expected observations on your machine; do not treat these lab settings as a completed production configuration.

