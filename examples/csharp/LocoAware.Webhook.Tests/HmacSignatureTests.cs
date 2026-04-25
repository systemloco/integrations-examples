using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Xunit;

namespace LocoAware.Webhook.Tests;

public class HmacSignatureTests : IClassFixture<SignedAppFactory>
{
    private const string Secret = "shhh";
    private readonly HttpClient _client;

    public HmacSignatureTests(SignedAppFactory factory)
    {
        _client = factory.CreateClient();
    }

    private static string Sign(string body, string secret)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(body)));
    }

    private static StringContent Json(string body) =>
        new(body, Encoding.UTF8, "application/json");

    [Fact]
    public async Task RejectsRequestWithoutSignatureHeader()
    {
        var resp = await _client.PostAsync("/webhooks/shipments", Json("{\"shipment\":{\"id\":\"x\"}}"));
        Assert.Equal(HttpStatusCode.Unauthorized, resp.StatusCode);
    }

    [Fact]
    public async Task RejectsRequestWithBadSignature()
    {
        var body = "{\"shipment\":{\"id\":\"x\"}}";
        var content = Json(body);
        content.Headers.Add("X-LocoAware-Signature", Sign(body, "wrong-secret"));
        var resp = await _client.PostAsync("/webhooks/shipments", content);
        Assert.Equal(HttpStatusCode.Unauthorized, resp.StatusCode);
    }

    [Fact]
    public async Task AcceptsCorrectlySignedShipmentEvent()
    {
        var body = "{\"id\":\"evt_signed\",\"shipment\":{\"id\":\"ship_signed\"}}";
        var content = Json(body);
        content.Headers.Add("X-LocoAware-Signature", Sign(body, Secret));
        var resp = await _client.PostAsync("/webhooks/shipments", content);
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);
    }

    [Fact]
    public async Task AcceptsCorrectlySignedDeviceReport()
    {
        var body = "{\"device\":{\"id\":\"dev_signed\"}}";
        var content = Json(body);
        content.Headers.Add("X-LocoAware-Signature", Sign(body, Secret));
        var resp = await _client.PostAsync("/webhooks/device-reports", content);
        Assert.Equal(HttpStatusCode.Accepted, resp.StatusCode);
    }
}

public class SignedAppFactory : WebApplicationFactory<Program>
{
    protected override IHost CreateHost(IHostBuilder builder)
    {
        Environment.SetEnvironmentVariable("LOCOAWARE_WEBHOOK_SECRET", "shhh");
        return base.CreateHost(builder);
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.ConfigureServices(services =>
        {
            // Drop the queue-draining worker so tests stay deterministic.
            services.RemoveAll<IHostedService>();
        });
    }

    protected override void Dispose(bool disposing)
    {
        Environment.SetEnvironmentVariable("LOCOAWARE_WEBHOOK_SECRET", null);
        base.Dispose(disposing);
    }
}
