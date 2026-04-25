# Live LocoAware → Local Receiver via ngrok

Step-by-step recipes to take a working example from `git clone` to "real LocoAware payloads landing in stdout on my laptop" with as little thinking as possible.

> ⚠️ **Development use only.** ngrok tunnels are ephemeral, unauthenticated by default, and bypass your normal network controls. Do not point a production LocoAware tenant at one. When you're ready, deploy behind your own TLS-terminated public endpoint with HMAC verification on.

---

## Contents

- [Prerequisites](#prerequisites)
- [Common steps (do these once)](#common-steps-do-these-once)
- [Per-language: get the receiver running on `:8080`](#per-language-get-the-receiver-running-on-8080)
  - [Node.js](#nodejs)
  - [Java (Spring Boot)](#java-spring-boot)
  - [C# (ASP.NET Core)](#c-aspnet-core)
  - [PHP (Slim)](#php-slim)
  - [Ruby (Sinatra)](#ruby-sinatra)
- [Sanity check: send a signed request locally](#sanity-check-send-a-signed-request-locally)
- [Open the ngrok tunnel](#open-the-ngrok-tunnel)
- [Wire it into a LocoAware tenant](#wire-it-into-a-locoaware-tenant)
- [What to watch for](#what-to-watch-for)
- [Tearing down](#tearing-down)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

Install once:

- **`ngrok`** — `brew install ngrok` (or download from <https://ngrok.com/download>). After installing:
  1. Sign up for a free account at <https://dashboard.ngrok.com/signup>.
  2. Grab your personal token from <https://dashboard.ngrok.com/get-started/your-authtoken> — there's a "Copy" button next to it.
  3. Run `ngrok config add-authtoken <token>` once. This writes `~/Library/Application Support/ngrok/ngrok.yml` so you never have to do it again.

  Without the token, free-tier tunnels still work but time out after a few minutes.
- The toolchain for **whichever one language** you want to test — see the [toolchain dependencies table](./README.md#toolchain-dependencies). You don't need all five.

Optional but recommended:

- **`jq`** — `brew install jq`. Pretty-prints inbound JSON when you tail logs.

---

## Common steps (do these once)

Pick a secret. Use the same value everywhere — your shell, the example, and the LocoAware webhook destination:

```bash
export LOCOAWARE_WEBHOOK_SECRET='shhh-dev-secret'
```

That's it for shared setup. Now jump to the section for your language.

---

## Per-language: get the receiver running on `:8080`

Each section below is a clean copy-paste from `cd` to running. Run them in **terminal 1**.

### Node.js

```bash
cd examples/node
npm install
LOCOAWARE_WEBHOOK_SECRET="$LOCOAWARE_WEBHOOK_SECRET" node server.js
```

You should see:

```
[server] LocoAware webhook receiver listening on :8080
```

Optional: in **terminal 2**, run the queue worker so you can see device-reports and device-events being drained too:

```bash
cd examples/node && node worker.js
```

### Java (Spring Boot)

```bash
cd examples/java
LOCOAWARE_WEBHOOK_SECRET="$LOCOAWARE_WEBHOOK_SECRET" \
  mvn spring-boot:run \
  -Dspring-boot.run.arguments="--locoaware.webhook.secret=$LOCOAWARE_WEBHOOK_SECRET"
```

The Spring filter reads `locoaware.webhook.secret` from Spring properties — passing it as a `--` argument is the cleanest way to inject it at run time.

You should see Spring Boot start up and `Tomcat started on port 8080`.

### C# (ASP.NET Core)

The example multi-targets `net8.0;net9.0`, so `dotnet run` needs `--framework`. Pick whichever runtime you have (`dotnet --list-runtimes` will show you).

```bash
cd examples/csharp
LOCOAWARE_WEBHOOK_SECRET="$LOCOAWARE_WEBHOOK_SECRET" \
  dotnet run --framework net9.0 --urls http://localhost:8080
```

(Swap `net9.0` for `net8.0` if that's what you have installed.)

You should see:

```
Now listening on: http://localhost:8080
```

⚠️ Without `--urls http://localhost:8080`, `dotnet run` picks a random port like `:5135` and your `ngrok http 8080` won't reach it. Always pass `--urls` (or use the printed URL when starting ngrok).

### PHP (Slim)

```bash
cd examples/php
composer install
LOCOAWARE_WEBHOOK_SECRET="$LOCOAWARE_WEBHOOK_SECRET" php -S localhost:8080 -t public
```

You should see:

```
PHP X.Y.Z Development Server (http://localhost:8080) started
```

PHP's built-in dev server is single-threaded — fine for poking at LocoAware payloads, not fine for production load.

### Ruby (Sinatra)

```bash
cd examples/ruby
bundle install
LOCOAWARE_WEBHOOK_SECRET="$LOCOAWARE_WEBHOOK_SECRET" bundle exec rackup -p 8080
```

You should see:

```
Puma starting in single mode...
* Listening on http://0.0.0.0:8080
```

Optional: in **terminal 2**, run the queue worker:

```bash
cd examples/ruby && bundle exec ruby worker.rb
```

---

## Sanity check: send a signed request locally

Before involving ngrok or LocoAware, prove the receiver works end-to-end on your laptop. In **terminal 3**:

```bash
BODY='{"id":"evt_test","type":"positionReport","shipment":{"id":"ship_test"}}'
# LocoAware sends base64(HMAC-SHA256(body, secret)) — match that here.
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$LOCOAWARE_WEBHOOK_SECRET" -binary | base64)

curl -i -X POST http://localhost:8080/webhooks/shipments \
  -H 'Content-Type: application/json' \
  -H "X-LocoAware-Signature: $SIG" \
  -d "$BODY"
```

Expected: `HTTP/1.1 200 OK`. Terminal 1 logs should show the event being appended.

Try the high-volume endpoints too:

```bash
BODY='{"device":{"id":"dev_test","name":"ship_test"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$LOCOAWARE_WEBHOOK_SECRET" -binary | base64)

curl -i -X POST http://localhost:8080/webhooks/device-reports \
  -H 'Content-Type: application/json' \
  -H "X-LocoAware-Signature: $SIG" \
  -d "$BODY"
```

Expected: `HTTP/1.1 202 Accepted`. The payload is enqueued, the worker (if running) drains it.

If those work, **HMAC + routing is correct**. The remaining question is purely "does the public internet reach this port?" — that's what ngrok is for.

---

## Open the ngrok tunnel

In **terminal 4**:

```bash
ngrok http 8080
```

ngrok prints something like:

```
Forwarding    https://gnarlyfox-1234.ngrok-free.app -> http://localhost:8080
```

That HTTPS URL is your public webhook base. Open <http://127.0.0.1:4040> in a browser — that's ngrok's local dashboard, which shows every inbound request with full headers and body. Keep it open while you test; it's much nicer than tailing logs.

---

## Wire it into a LocoAware tenant

In LocoAware → **Integrations** → create or edit a webhook destination:

| Field | Value |
|---|---|
| URL | `https://<your-ngrok-id>.ngrok-free.app/webhooks/device-reports` |
| Secret | the same value as `$LOCOAWARE_WEBHOOK_SECRET` above |
| Signature header | `X-LocoAware-Signature` (default — leave alone) |

Repeat for whichever feeds you want — one destination per feed:

- `/webhooks/device-reports` — Device Reports V2
- `/webhooks/device-events` — Device Events
- `/webhooks/shipments` — Shipments
- `/webhooks/assets` — Assets

Save. LocoAware will start delivering on its normal cadence (high for device-reports, lower for the rest).

---

## What to watch for

Three places, in order of usefulness:

1. **ngrok dashboard (`http://127.0.0.1:4040`).** Every inbound request, fully expanded. If you don't see traffic here, the URL or LocoAware config is wrong, not your receiver.
2. **Terminal 1 (the receiver).** Should log each payload — `[repo]` lines for shipments/assets, `[queue]` lines for device-events/device-reports.
3. **Terminal 2 (the worker, if running).** Drains the queue, logs the lookup-by-name-or-id calls and the per-device updates.

If a 401 starts appearing on every request, the secret in LocoAware doesn't match `$LOCOAWARE_WEBHOOK_SECRET`. Recreate the destination with the correct secret — LocoAware encrypts secrets at rest and won't show them back to you.

---

## Tearing down

When you're done:

1. **Remove (or pause) the webhook destination in LocoAware.** This is the single most important step. If you skip it, LocoAware will keep delivering to a URL that no longer exists, retries will pile up, and the next person who sees that destination will be confused.
2. `Ctrl-C` ngrok.
3. `Ctrl-C` the receiver and any workers.
4. `unset LOCOAWARE_WEBHOOK_SECRET` (optional — only matters if you used a real secret).

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| ngrok prints `ERR_NGROK_4018` (no auth token) | First-time use, no token configured | Sign up at ngrok, then `ngrok config add-authtoken <token>` |
| ngrok dashboard shows requests, receiver never logs them | Receiver listening on a different port than `8080` | Check terminal 1's "listening on" line; restart ngrok with the right port |
| Receiver returns `401 missing signature` | LocoAware destination doesn't have a secret set, but the receiver does | Either add the secret in LocoAware or unset `LOCOAWARE_WEBHOOK_SECRET` and restart the receiver |
| Receiver returns `401 bad signature` | Secret mismatch between LocoAware and the receiver | Recreate the destination with the secret you actually used |
| Receiver returns `405 Method Not Allowed` | LocoAware destination URL path is wrong (e.g. `/webhook/device-report` instead of `/webhooks/device-reports`) | Fix the URL — paths are exact matches |
| `dotnet run` errors with `Your project targets multiple frameworks. Specify which framework to run using '--framework'.` | The C# example multi-targets `net8.0;net9.0` so `dotnet run` doesn't know which to use | Add `--framework net9.0` (or `net8.0` if installed): `dotnet run --framework net9.0 --urls http://localhost:8080` |
| `mvn: command not found` after install | Homebrew bin/ not on PATH in this shell | `eval "$(/opt/homebrew/bin/brew shellenv)"` for the current shell, or add it to `~/.zshrc` permanently |
| Ruby example returns `403 Host not permitted` | Sinatra 4 / Rack 3's `Rack::Protection::HostAuthorization` rejects the ngrok hostname | Already disabled in the example via `set :host_authorization, permitted_hosts: []` — if you re-enabled it, either add your ngrok domain or restore the empty allow-list |
| Ruby example throws `NoMethodError: undefined method 'rewind' for an instance of Rack::Lint::Wrapper::InputWrapper` | Rack 3 input streams aren't rewindable under `Rack::Lint` (which Sinatra enables in development) | Don't call `request.body.rewind` — the `before` filter is the first thing to touch the body, so a single `request.body.read` returns the full payload (already done in the example) |
| Tunnel works briefly then dies | ngrok free tier session timeout | Re-run `ngrok http 8080`; you'll get a new URL and need to update the LocoAware destination |
| 5xx responses, retries piling up | Receiver crashed or queue is unreachable | Check terminal 1 — for high-volume feeds the answer is almost always "the queue worker isn't running" or "the broker isn't reachable" |

---

Once this works end-to-end, you've validated everything that matters: signature verification, payload shape, routing, queueing, and the worker contract. From here it's a matter of swapping the in-memory queue for your real broker and the stand-in repositories for your real persistence layer — see each example's README for the swap points.
