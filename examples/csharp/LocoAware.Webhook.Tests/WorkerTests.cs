using System.Text.Json;
using LocoAware.Webhook;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace LocoAware.Webhook.Tests;

public class WorkerTests
{
    private readonly DeviceReportsRepository _deviceReports = new(NullLogger<DeviceReportsRepository>.Instance);
    private readonly DevicesRepository _devices = new(NullLogger<DevicesRepository>.Instance);
    private readonly ShipmentsRepository _shipments = new(NullLogger<ShipmentsRepository>.Instance);
    private readonly Worker _worker;

    public WorkerTests()
    {
        var queue = new QueueClient();
        _worker = new Worker(queue, _deviceReports, _shipments, _devices, NullLogger<Worker>.Instance);
    }

    private const string PrimaryJson = """
        {
          "id":"rpt-primary-1","time":"2026-04-09T10:47:58.612Z",
          "device":{"id":"dev-primary","displayId":"HGD-1",
                    "model":{"family":"locoTrack","name":"HGD4","product":"LocoTrack","version":4,"revision":1},
                    "labels":[]},
          "owner":{"id":"own-1","name":"Acme"},
          "sensors":{"battery":{"level":90},"temperature":20},
          "secondaryObservations":[
            {"id":"dev-tag-A","displayId":"TAG-A",
             "model":{"family":"locoCard","name":"E1BL","product":"LocoCard","version":1,"revision":0},
             "reportType":"presence","reportId":"rpt-tag-A","rssi":-50,"isPaired":true},
            {"id":"dev-tag-B","displayId":"TAG-B",
             "model":{"family":"locoCard","name":"E1BL","product":"LocoCard","version":1,"revision":0},
             "reportType":"event","reportId":"rpt-tag-B","rssi":-70,"isPaired":true}
          ]
        }
        """;

    private const string SecondaryJson = """
        {
          "id":"rpt-tag-A","time":"2026-04-09T10:48:51.000Z",
          "device":{"id":"dev-tag-A","displayId":"TAG-A",
                    "model":{"family":"locoCard","name":"E1BL","product":"LocoCard","version":1,"revision":0},
                    "labels":[]},
          "owner":{"id":"own-1","name":"Acme"},
          "reportedBy":{
            "device":{"id":"dev-primary","displayId":"HGD-1",
                      "model":{"family":"locoTrack","name":"HGD4","product":"LocoTrack","version":4,"revision":1}},
            "report":{"id":"rpt-primary-1","time":"2026-04-09T10:47:58.612Z"},
            "rssi":-50
          },
          "sensors":{"battery":{"level":80}}
        }
        """;

    private static JsonElement Read(string json) => JsonDocument.Parse(json).RootElement;

    [Fact]
    public async Task PrimaryStoresReportAndTouchesEachSecondary()
    {
        await _worker.HandleDeviceReportAsync(Read(PrimaryJson));

        var stored = _deviceReports.Get("rpt-primary-1")!;
        Assert.Equal("primary", stored["role"]);
        Assert.Equal(2, ((IEnumerable<IDictionary<string, object?>>)stored["secondaries"]!).Count());

        var primaryDev = _devices.Get("dev-primary")!;
        var lastReport = (IDictionary<string, object?>)primaryDev["lastReport"]!;
        Assert.Equal("rpt-primary-1", lastReport["id"]);
        var lastSensors = (IDictionary<string, object?>)primaryDev["lastSensors"]!;
        var battery = (IDictionary<string, object?>)lastSensors["battery"]!;
        Assert.Equal(90, battery["level"]);

        var tagA = _devices.Get("dev-tag-A")!;
        var tagB = _devices.Get("dev-tag-B")!;
        var seenByA = (IDictionary<string, object?>)tagA["lastSeenBy"]!;
        var seenByB = (IDictionary<string, object?>)tagB["lastSeenBy"]!;
        Assert.Equal("dev-primary", seenByA["primaryDeviceId"]);
        Assert.Equal(-50, seenByA["rssi"]);
        Assert.Equal(-70, seenByB["rssi"]);
        Assert.False(tagA.ContainsKey("lastSensors"), "primary presence touch must not write sensor data");
    }

    [Fact]
    public async Task SecondaryWritesAuthoritativeSensorsAndPrimaryDoesNotClobber()
    {
        await _worker.HandleDeviceReportAsync(Read(SecondaryJson));
        await _worker.HandleDeviceReportAsync(Read(PrimaryJson));

        var tagA = _devices.Get("dev-tag-A")!;
        var lastSensors = (IDictionary<string, object?>)tagA["lastSensors"]!;
        var battery = (IDictionary<string, object?>)lastSensors["battery"]!;
        Assert.Equal(80, battery["level"]);

        var seenBy = (IDictionary<string, object?>)tagA["lastSeenBy"]!;
        Assert.Equal("dev-primary", seenBy["primaryDeviceId"]);

        var lastReport = (IDictionary<string, object?>)tagA["lastReport"]!;
        Assert.Equal("secondary", lastReport["role"]);
    }

    [Fact]
    public async Task FallsBackToRelayPrimaryWhenTagHasNoShipmentBinding()
    {
        _shipments.Seed("ship-7", new Dictionary<string, object?> { ["boundDeviceIds"] = new List<object?> { "dev-primary" } });

        await _worker.HandleDeviceReportAsync(Read(SecondaryJson));

        var ship = _shipments.Get("ship-7")!;
        var events = (List<object?>)ship["events"]!;
        Assert.Single(events);
    }

    [Fact]
    public async Task AppendsToShipmentMatchedByDeviceName()
    {
        _shipments.Seed("ship-42", new Dictionary<string, object?> { ["boundDeviceNames"] = new List<object?> { "SHIP-42" } });
        var withName = PrimaryJson.Replace("\"labels\":[]", "\"name\":\"SHIP-42\",\"labels\":[]");

        await _worker.HandleDeviceReportAsync(Read(withName));

        var ship = _shipments.Get("ship-42")!;
        var events = (List<object?>)ship["events"]!;
        Assert.Single(events);
    }

    [Fact]
    public async Task IsNullSafeForMinimalReport()
    {
        var json = """
            {"id":"rpt-empty","time":"2026-04-09T10:00:00.000Z",
             "device":{"id":"dev-empty","displayId":"X",
                       "model":{"family":"locoTrack","name":"HGD4","product":"LocoTrack","version":4,"revision":1},
                       "labels":[]},
             "owner":{"id":"own-1","name":"Acme"}}
            """;
        await _worker.HandleDeviceReportAsync(Read(json));

        var stored = _deviceReports.Get("rpt-empty")!;
        Assert.Equal("standalone", stored["role"]);
        var dev = _devices.Get("dev-empty")!;
        Assert.False(dev.ContainsKey("lastSensors"));
    }
}
