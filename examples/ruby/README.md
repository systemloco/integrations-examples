# LocoAware Webhook Receiver — Ruby (Sinatra)

Reference implementation of a LocoAware webhook receiver in Ruby using Sinatra.

## Layout

```
.
├── Gemfile
├── config.ru               ← Rack entry point
├── app.rb                  ← Sinatra app: 4 endpoints, HMAC before-filter
├── queue_client.rb         ← Stand-in queue (swap for SQS/RabbitMQ/…)
├── repositories.rb         ← Stand-in shipments/devices/assets repos
└── worker.rb               ← Consumer loop that drains the queue
```

## Run

```bash
bundle install
LOCOAWARE_WEBHOOK_SECRET=shhh bundle exec rackup -p 8080
```

In another terminal, run the worker:

```bash
bundle exec ruby worker.rb
```

## What it does

- **`POST /webhooks/shipments`** — processes inline, responds `200`
- **`POST /webhooks/assets`** — processes inline, responds `200`
- **`POST /webhooks/device-events`** — enqueues, responds `202`
- **`POST /webhooks/device-reports`** — enqueues, responds `202`

## Tests

```bash
bundle install
bundle exec rake test
```

Minitest suite under `test/` driven by `rack-test` — covers HMAC verification, all four endpoints, and the stand-in repositories. Requires Ruby ≥ 3.1 (the same version Sinatra 4 requires).

## Replacing the stand-ins

- `queue_client.rb` — swap for `aws-sdk-sqs`, `bunny` (RabbitMQ), `sidekiq`, or `shoryuken`
- `repositories.rb` — replace with ActiveRecord / Sequel / ROM / your ORM
- `worker.rb` — a real worker runs via Sidekiq/Shoryuken/etc. in a separate process, not the in-memory loop shown here
