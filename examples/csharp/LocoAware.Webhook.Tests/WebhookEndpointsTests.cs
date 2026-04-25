using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Json;
using LocoAware.Webhook;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace LocoAware.Webhook.Tests;

public class WebhookEndpointsTests : IClassFixture<TestAppFactory>
{
    private readonly TestAppFactory _factory;
    private readonly HttpClient _client;

    public WebhookEndpointsTests(TestAppFactory factory)
    {
        _factory = factory;
        _factory.Reset();
        _client = factory.CreateClient();
    }

    private static StringContent Json(string body) =>
        new(body, Encoding.UTF8, "application/json");

    [Fact]
    public async Task ShipmentsEndpointReturns200()
    {
        var resp = await _client.PostAsync("/webhooks/shipments",
            Json("{\"id\":\"evt_1\",\"type\":\"positionReport\",\"shipment\":{\"id\":\"ship_1\"}}"));
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        Assert.Single(_factory.Shipments.Appended);
        Assert.Equal("ship_1", _factory.Shipments.Appended[0].ShipmentId);
    }

    [Fact]
    public async Task ShipmentReportSyncsTheEmbeddedDevice()
    {
        var resp = await _client.PostAsync("/webhooks/shipments",
            Json("{\"id\":\"evt_2\",\"type\":\"report\",\"shipment\":{\"id\":\"ship_2\"},\"payload\":{\"device\":{\"id\":\"dev_2\"}}}"));
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        Assert.Contains("dev_2", _factory.Devices.Updates);
    }

    [Fact]
    public async Task AssetsEndpointReturns200AndLooksUpShipment()
    {
        var resp = await _client.PostAsync("/webhooks/assets",
            Json("{\"id\":\"evt_3\",\"asset\":{\"id\":\"asset_3\",\"name\":\"pallet-3\"}}"));
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        Assert.Single(_factory.Shipments.AssetLookups);
        Assert.Equal(("pallet-3", "asset_3"), _factory.Shipments.AssetLookups[0]);
    }

    [Fact]
    public async Task DeviceEventsEndpointReturns202AndEnqueues()
    {
        var resp = await _client.PostAsync("/webhooks/device-events",
            Json("{\"id\":\"evt_4\",\"device\":{\"id\":\"dev_4\"}}"));
        Assert.Equal(HttpStatusCode.Accepted, resp.StatusCode);

        Assert.Single(_factory.Queue.Published);
        Assert.Equal("locoaware.device-events", _factory.Queue.Published[0].Topic);
    }

    [Fact]
    public async Task DeviceReportsEndpointReturns202AndEnqueues()
    {
        var resp = await _client.PostAsync("/webhooks/device-reports",
            Json("{\"device\":{\"id\":\"dev_5\",\"name\":\"ship_5\"}}"));
        Assert.Equal(HttpStatusCode.Accepted, resp.StatusCode);

        Assert.Single(_factory.Queue.Published);
        Assert.Equal("locoaware.device-reports", _factory.Queue.Published[0].Topic);
    }
}

public class TestAppFactory : WebApplicationFactory<Program>
{
    public RecordingQueueClient Queue { get; private set; } = new();
    public RecordingShipments  Shipments { get; private set; } = new();
    public RecordingDevices    Devices { get; private set; } = new();
    public RecordingAssets     Assets { get; private set; } = new();

    public void Reset()
    {
        Queue.Published.Clear();
        Shipments.Appended.Clear();
        Shipments.DeviceLookups.Clear();
        Shipments.AssetLookups.Clear();
        Devices.Updates.Clear();
        Assets.Updates.Clear();
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.ConfigureServices(services =>
        {
            // Drop the real registrations and the queue-draining worker —
            // tests inspect what was published rather than what was processed.
            services.RemoveAll<QueueClient>();
            services.RemoveAll<ShipmentsRepository>();
            services.RemoveAll<DevicesRepository>();
            services.RemoveAll<AssetsRepository>();
            services.RemoveAll<IHostedService>();

            services.AddSingleton(Queue);
            services.AddSingleton<QueueClient>(Queue);
            services.AddSingleton(Shipments);
            services.AddSingleton<ShipmentsRepository>(Shipments);
            services.AddSingleton(Devices);
            services.AddSingleton<DevicesRepository>(Devices);
            services.AddSingleton(Assets);
            services.AddSingleton<AssetsRepository>(Assets);
        });
    }
}

public class RecordingQueueClient : QueueClient
{
    public record PublishedMessage(string Topic, byte[] Body);
    public List<PublishedMessage> Published { get; } = new();

    public override ValueTask PublishAsync(string topic, byte[] body)
    {
        Published.Add(new PublishedMessage(topic, body));
        return ValueTask.CompletedTask;
    }
}

public class RecordingShipments : ShipmentsRepository
{
    public record Append(string ShipmentId, JsonElement Event);
    public List<Append> Appended { get; } = new();
    public List<(string?, string?)> DeviceLookups { get; } = new();
    public List<(string?, string?)> AssetLookups { get; } = new();

    public RecordingShipments() : base(NullLogger<ShipmentsRepository>.Instance) {}

    public override Task AppendEventAsync(string shipmentId, JsonElement evt)
    {
        Appended.Add(new Append(shipmentId, evt));
        return Task.CompletedTask;
    }

    public override Task<string?> FindIdByDeviceNameOrIdAsync(string? deviceName, string? deviceId)
    {
        DeviceLookups.Add((deviceName, deviceId));
        return Task.FromResult<string?>(null);
    }

    public override Task<string?> FindIdByAssetNameOrIdAsync(string? assetName, string? assetId)
    {
        AssetLookups.Add((assetName, assetId));
        return Task.FromResult<string?>(null);
    }
}

public class RecordingDevices : DevicesRepository
{
    public List<string> Updates { get; } = new();
    public RecordingDevices() : base(NullLogger<DevicesRepository>.Instance) {}

    public override Task UpdateLatestAsync(string deviceId, JsonElement evt)
    {
        Updates.Add(deviceId);
        return Task.CompletedTask;
    }
}

public class RecordingAssets : AssetsRepository
{
    public List<string> Updates { get; } = new();
    public RecordingAssets() : base(NullLogger<AssetsRepository>.Instance) {}

    public override Task UpdateLatestAsync(string assetId, JsonElement evt)
    {
        Updates.Add(assetId);
        return Task.CompletedTask;
    }
}
