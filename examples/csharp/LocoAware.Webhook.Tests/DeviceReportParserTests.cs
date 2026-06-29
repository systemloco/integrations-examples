using System.Text.Json;
using LocoAware.Webhook;
using Xunit;

namespace LocoAware.Webhook.Tests;

public class DeviceReportParserTests
{
    private const string PrimaryJson = """
        {
          "id": "rpt-primary-1",
          "time": "2026-04-09T10:47:58.612Z",
          "rxTime": "2026-04-09T10:47:58.716Z",
          "device": {
            "id": "dev-primary", "displayId": "HGD-1",
            "model": {"name":"HGD4","family":"locoTrack","product":"LocoTrack","version":4,"revision":1},
            "labels": ["cold-chain"]
          },
          "owner": {"id":"own-1","name":"Acme"},
          "reportReasons": ["report"],
          "location": {"type":"wifi","global":{"lat":1.0,"lon":2.0}},
          "sensors": {
            "battery": {"level":100,"state":"good"},
            "temperature": 25.1,
            "orientation": {"x":0.0,"y":0.0,"z":-1.0}
          },
          "secondaryObservations": [{
            "id":"dev-tag-A","displayId":"TAG-A",
            "model":{"name":"E1BL","family":"locoCard","product":"LocoCard","version":1,"revision":0},
            "reportType":"presence","reportId":"rpt-tag-A","rssi":-53,"isPaired":true
          }],
          "thirdPartyObservations": [{
            "type":"cableSeal","id":"A2:A2:A2:00:07:AB","displayId":"A2A2A20007AB",
            "state":"closed","open":false,"temperature":21,"counter":7
          }]
        }
        """;

    private const string SecondaryJson = """
        {
          "id": "rpt-tag-A",
          "time": "2026-04-09T10:48:51.000Z",
          "device": {
            "id":"dev-tag-A","displayId":"TAG-A",
            "model":{"name":"E1BL","family":"locoCard","product":"LocoCard","version":1,"revision":0},
            "labels": []
          },
          "owner": {"id":"own-1","name":"Acme"},
          "reportedBy": {
            "device": {"id":"dev-primary","displayId":"HGD-1",
                       "model":{"name":"HGD4","family":"locoTrack","product":"LocoTrack","version":4,"revision":1}},
            "report": {"id":"rpt-primary-1","time":"2026-04-09T10:47:58.612Z"},
            "rssi": -53
          },
          "sensors": {"battery":{"level":80}},
          "timeSeries": {"temperature": [{"time":"2026-04-09T10:36:00.000Z","value":22.6}]}
        }
        """;

    private const string SparseJson = """
        {
          "id":"rpt-sparse","time":"2026-04-09T10:00:00.000Z",
          "device":{"id":"dev-sparse","displayId":"DS-1",
                    "model":{"name":"HGD4","family":"locoTrack","product":"LocoTrack","version":4,"revision":1},
                    "labels":[]},
          "owner":{"id":"own-1","name":"Owner"}
        }
        """;

    private static JsonElement Read(string json) => JsonDocument.Parse(json).RootElement;

    [Fact]
    public void PrimaryReportIsTaggedRolePrimary()
    {
        var doc = DeviceReportParser.Parse(Read(PrimaryJson))!;
        Assert.Equal("primary", doc["role"]);
        var secs = (List<IDictionary<string, object?>>)doc["secondaries"]!;
        Assert.Single(secs);
        Assert.Equal("rpt-tag-A", secs[0]["reportId"]);
        Assert.Equal(true, secs[0]["isPaired"]);
    }

    [Fact]
    public void SecondaryReportIsTaggedRoleSecondary()
    {
        var doc = DeviceReportParser.Parse(Read(SecondaryJson))!;
        Assert.Equal("secondary", doc["role"]);
        var relayedBy = (IDictionary<string, object?>)doc["relayedBy"]!;
        var relayDevice = (IDictionary<string, object?>)relayedBy["device"]!;
        Assert.Equal("dev-primary", relayDevice["id"]);
        Assert.Equal("rpt-primary-1", relayedBy["reportId"]);
        Assert.Equal(-53, relayedBy["rssi"]);
    }

    [Fact]
    public void PrimaryToSecondaryLinkCanBeTracedBothWays()
    {
        var primary = DeviceReportParser.Parse(Read(PrimaryJson))!;
        var secondary = DeviceReportParser.Parse(Read(SecondaryJson))!;

        var secs = (List<IDictionary<string, object?>>)primary["secondaries"]!;
        var matching = secs.FirstOrDefault(s => (string?)s["reportId"] == (string?)secondary["_id"]);
        Assert.NotNull(matching);

        var secDevice = (IDictionary<string, object?>)secondary["device"]!;
        Assert.Equal(secDevice["id"], matching!["id"]);

        var relayedBy = (IDictionary<string, object?>)secondary["relayedBy"]!;
        Assert.Equal(primary["_id"], relayedBy["reportId"]);
    }

    [Fact]
    public void StandaloneReportHasNoSecondariesOrRelayedBy()
    {
        var doc = DeviceReportParser.Parse(Read(SparseJson))!;
        Assert.Equal("standalone", doc["role"]);
        Assert.False(doc.ContainsKey("secondaries"));
        Assert.False(doc.ContainsKey("relayedBy"));
    }

    [Fact]
    public void SparseReportOmitsAbsentFieldsEntirely()
    {
        var doc = DeviceReportParser.Parse(Read(SparseJson))!;
        foreach (var k in new[] { "rxTime", "location", "sensors", "relayedBy", "thirdPartyObservations", "timeSeries" })
        {
            Assert.False(doc.ContainsKey(k), $"expected '{k}' to be absent on a sparse report");
        }
        Assert.Equal("rpt-sparse", doc["_id"]);
    }

    [Fact]
    public void SensorsAreGroupedIntoBatteryEnvironmentAndVectors()
    {
        var doc = DeviceReportParser.Parse(Read(PrimaryJson))!;
        var sensors = (IDictionary<string, object?>)doc["sensors"]!;
        var battery = (IDictionary<string, object?>)sensors["battery"]!;
        Assert.Equal(100, battery["level"]);

        var env = (IDictionary<string, object?>)sensors["environment"]!;
        Assert.Equal(25.1, env["temperature"]);

        var orientation = (IDictionary<string, object?>)sensors["orientation"]!;
        Assert.Equal(-1.0, orientation["z"]);
    }

    [Fact]
    public void ThirdPartyObservationKeepsTypeSpecificFields()
    {
        var doc = DeviceReportParser.Parse(Read(PrimaryJson))!;
        var tp = (List<IDictionary<string, object?>>)doc["thirdPartyObservations"]!;
        var seal = tp[0];
        Assert.Equal("cableSeal", seal["type"]);
        Assert.Equal("closed", seal["state"]);
        Assert.Equal(21, seal["temperature"]);
        Assert.Equal(7, seal["counter"]);
    }

    [Fact]
    public void UnknownThirdPartyTypeFallsBackToRaw()
    {
        var json = """
            {"id":"r1","time":"t",
             "device":{"id":"d","displayId":"D",
                       "model":{"family":"locoTrack","name":"HGD4","product":"LocoTrack","version":4,"revision":1},
                       "labels":[]},
             "owner":{"id":"o","name":"O"},
             "thirdPartyObservations":[{"type":"futureBeacon","id":"F1","displayId":"F1","mystery":true}]}
            """;
        var doc = DeviceReportParser.Parse(Read(json))!;
        var tp = (List<IDictionary<string, object?>>)doc["thirdPartyObservations"]!;
        var beacon = tp[0];
        Assert.Equal("futureBeacon", beacon["type"]);
        var raw = (IDictionary<string, object?>)beacon["raw"]!;
        Assert.Equal(true, raw["mystery"]);
    }

    [Fact]
    public void TimeSeriesPassesThroughAndDropsEmptySeries()
    {
        var doc = DeviceReportParser.Parse(Read(SecondaryJson))!;
        var ts = (IDictionary<string, object?>)doc["timeSeries"]!;
        var temp = (List<object?>)ts["temperature"]!;
        Assert.Single(temp);
    }

    [Fact]
    public void ProjectLatestStateReturnsPerDeviceSnapshot()
    {
        var doc = DeviceReportParser.Parse(Read(PrimaryJson))!;
        var state = DeviceReportParser.ProjectLatestState(doc)!;
        var device = (IDictionary<string, object?>)doc["device"]!;
        Assert.Equal(device["id"], state["_id"]);
        var lastReport = (IDictionary<string, object?>)state["lastReport"]!;
        Assert.Equal(doc["_id"], lastReport["id"]);
        Assert.Equal("primary", lastReport["role"]);
        Assert.NotNull(state["lastSensors"]);
        Assert.NotNull(state["lastLocation"]);
    }

    [Fact]
    public void ProjectLatestStateOmitsAbsentOptionals()
    {
        var doc = DeviceReportParser.Parse(Read(SparseJson))!;
        var state = DeviceReportParser.ProjectLatestState(doc)!;
        Assert.Equal("dev-sparse", state["_id"]);
        Assert.False(state.ContainsKey("lastLocation"));
        Assert.False(state.ContainsKey("lastSensors"));
        Assert.False(state.ContainsKey("relayedBy"));
    }
}
