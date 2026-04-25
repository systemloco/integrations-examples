# LocoAware Webhook Receiver — C# (ASP.NET Core)

Reference implementation of a LocoAware webhook receiver in C# using ASP.NET Core minimal APIs. The project multi-targets `net8.0;net9.0` so it builds and runs on either runtime.

## Layout

```
.
├── LocoAware.Webhook.csproj
├── Program.cs                  ← HTTP server, 4 endpoints, HMAC middleware
├── QueueClient.cs              ← Stand-in queue (swap for SQS/ServiceBus/…)
├── Repositories.cs             ← Stand-in shipments/devices/assets repos
└── Worker.cs                   ← BackgroundService that drains the queue
```

## Run

Because the project multi-targets two frameworks, `dotnet run` needs a `--framework` argument:

```bash
dotnet run --framework net9.0
```

(Use `--framework net8.0` if you have the .NET 8 runtime instead — `dotnet --list-runtimes` will show what's installed.)

`dotnet run` defaults to a random port. To force port 8080 (what the rest of the docs assume), add `--urls`:

```bash
dotnet run --framework net9.0 --urls http://localhost:8080
```

With a signing secret:

```bash
LOCOAWARE_WEBHOOK_SECRET=shhh dotnet run --framework net9.0 --urls http://localhost:8080
```

## What it does

- **`POST /webhooks/shipments`** — processes inline, responds `200`
- **`POST /webhooks/assets`** — processes inline, responds `200`
- **`POST /webhooks/device-events`** — enqueues, responds `202`
- **`POST /webhooks/device-reports`** — enqueues, responds `202`

## Tests

```bash
cd LocoAware.Webhook.Tests
dotnet test
```

Uses xUnit and `Microsoft.AspNetCore.Mvc.Testing`'s `WebApplicationFactory<Program>` to spin the app in-process. The test project lives in `LocoAware.Webhook.Tests/` and multi-targets `net8.0;net9.0` — `dotnet test` will pick whichever runtime you have installed.

## Replacing the stand-ins

- `QueueClient` — swap for `AWSSDK.SQS`, `Azure.Messaging.ServiceBus`, `Confluent.Kafka`, or `RabbitMQ.Client`
- `Repositories` — plug in EF Core / Dapper / your ORM of choice
- `Worker` — in this example it's a `BackgroundService` reading a `Channel<T>`; replace with your broker's native consumer
