using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LocoAware.Webhook;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddSingleton<QueueClient>();
builder.Services.AddSingleton<ShipmentsRepository>();
builder.Services.AddSingleton<DevicesRepository>();
builder.Services.AddSingleton<AssetsRepository>();
builder.Services.AddHostedService<Worker>();

var app = builder.Build();

// ============================================================================
// HMAC signature middleware
// ============================================================================
var secret = Environment.GetEnvironmentVariable("LOCOAWARE_WEBHOOK_SECRET");
var signatureHeader = Environment.GetEnvironmentVariable("LOCOAWARE_SIGNATURE_HEADER")
                      ?? "X-LocoAware-Signature";

if (string.IsNullOrEmpty(secret))
{
    app.Logger.LogWarning("LOCOAWARE_WEBHOOK_SECRET is not set — HMAC verification is DISABLED");
}

app.Use(async (ctx, next) =>
{
    if (!ctx.Request.Path.StartsWithSegments("/webhooks"))
    {
        await next();
        return;
    }

    // Buffer the body so we can both HMAC it and hand it to the endpoint.
    ctx.Request.EnableBuffering();
    using var ms = new MemoryStream();
    await ctx.Request.Body.CopyToAsync(ms);
    var body = ms.ToArray();
    ctx.Request.Body.Position = 0;
    ctx.Items["body"] = body;

    if (!string.IsNullOrEmpty(secret))
    {
        var received = ctx.Request.Headers[signatureHeader].ToString();
        if (string.IsNullOrEmpty(received) || !VerifySignature(body, received, secret))
        {
            ctx.Response.StatusCode = 401;
            return;
        }
    }

    await next();
});

static bool VerifySignature(byte[] body, string received, string secret)
{
    // LocoAware sends base64(HMAC-SHA256(body, secret)).
    using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
    var expected = hmac.ComputeHash(body);
    byte[] receivedBytes;
    try { receivedBytes = Convert.FromBase64String(received); }
    catch { return false; }
    return CryptographicOperations.FixedTimeEquals(expected, receivedBytes);
}

static async Task<JsonElement> ReadJsonAsync(HttpContext ctx)
{
    var body = (byte[])ctx.Items["body"]!;
    return (await JsonDocument.ParseAsync(new MemoryStream(body))).RootElement;
}

static string? Str(JsonElement e, params string[] path)
{
    foreach (var p in path)
    {
        if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty(p, out var next))
            return null;
        e = next;
    }
    return e.ValueKind == JsonValueKind.String ? e.GetString() : null;
}

// ============================================================================
// Shipments — low volume. Handle inline.
// ============================================================================
app.MapPost("/webhooks/shipments", async (HttpContext ctx,
    ShipmentsRepository shipments, DevicesRepository devices) =>
{
    var evt = await ReadJsonAsync(ctx);

    var shipmentId = Str(evt, "shipment", "id");
    if (shipmentId is not null)
    {
        await shipments.AppendEventAsync(shipmentId, evt);
    }

    if (Str(evt, "type") == "report")
    {
        var deviceId = Str(evt, "payload", "device", "id");
        if (deviceId is not null && await devices.ExistsAsync(deviceId))
        {
            await devices.UpdateLatestAsync(deviceId, evt);
        }
    }

    return Results.Ok();
});

// ============================================================================
// Assets — low volume. Handle inline.
// ============================================================================
app.MapPost("/webhooks/assets", async (HttpContext ctx,
    ShipmentsRepository shipments, AssetsRepository assets) =>
{
    var evt = await ReadJsonAsync(ctx);
    var assetName = Str(evt, "asset", "name");
    var assetId   = Str(evt, "asset", "id");

    var shipmentId = await shipments.FindIdByAssetNameOrIdAsync(assetName, assetId);
    if (shipmentId is not null)
    {
        await shipments.AppendEventAsync(shipmentId, evt);
    }

    if (assetId is not null && await assets.ExistsAsync(assetId))
    {
        await assets.UpdateLatestAsync(assetId, evt);
    }

    return Results.Ok();
});

// ============================================================================
// Device Events — medium volume, bursty. Enqueue.
// ============================================================================
app.MapPost("/webhooks/device-events", async (HttpContext ctx, QueueClient queue) =>
{
    // Do not process here — a zone change across a large fleet can produce
    // dozens of events per second. Enqueue and ack within milliseconds.
    var body = (byte[])ctx.Items["body"]!;
    await queue.PublishAsync("locoaware.device-events", body);
    return Results.Accepted();
});

// ============================================================================
// Device Reports V2 — highest volume. Enqueue.
// ============================================================================
app.MapPost("/webhooks/device-reports", async (HttpContext ctx, QueueClient queue) =>
{
    // Firehose — a single company can push hundreds of reports per second.
    var body = (byte[])ctx.Items["body"]!;
    await queue.PublishAsync("locoaware.device-reports", body);
    return Results.Accepted();
});

app.Run();

// Expose Program to WebApplicationFactory<Program> in the test project.
public partial class Program { }
