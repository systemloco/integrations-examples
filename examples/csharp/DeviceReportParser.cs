using System.Text.Json;

namespace LocoAware.Webhook;

/// <summary>
/// Parse a Device Reports V2 webhook payload into a document-store-shaped record.
///
/// The output mirrors what you'd insert into MongoDB / DocumentDB / CosmosDB /
/// Couchbase — <c>_id</c> keyed to the report ID, with each documented
/// subsection extracted into a named field. Tables and schema migrations are
/// explicitly out of scope.
///
/// <para><b>Null handling.</b> The feed OMITS null/absent fields entirely
/// rather than sending them as <c>null</c> (see
/// <c>Device_Reports_V2_Integration_Guide.md</c> §10). This parser keeps that
/// convention: every optional field is only set on the output if the source
/// is present and non-null.</para>
///
/// <para><b>Primary / Secondary reporting.</b> LocoTrack hubs (primaries,
/// a.k.a. "masters") relay BLE LocoTag (secondary, a.k.a. "slave") state.
/// Each parsed document is tagged with a <c>role</c>: <c>"primary"</c> /
/// <c>"secondary"</c> / <c>"standalone"</c>. The primary's report lists each
/// observed tag under <c>secondaries[]</c>; the tag's own report links back
/// to the primary under <c>relayedBy</c>. Linking the two:
/// <code>
///   primaryDoc.secondaries[i].reportId == secondaryDoc._id
///   secondaryDoc.relayedBy.reportId    == primaryDoc._id
/// </code>
/// </para>
/// </summary>
public static class DeviceReportParser
{
    public static IDictionary<string, object?>? Parse(JsonElement report)
    {
        if (report.ValueKind != JsonValueKind.Object) return null;

        var doc = new Dictionary<string, object?>(StringComparer.Ordinal)
        {
            ["_id"] = Str(report, "id"),
            ["time"] = Str(report, "time"),
            ["role"] = DeriveRole(report),
            ["device"] = ParseDeviceIdentity(GetObject(report, "device")),
            ["owner"] = ParseOwner(GetObject(report, "owner")),
            ["reportReasons"] = AsStringList(GetArray(report, "reportReasons")),
        };

        var rxTime = Str(report, "rxTime");
        if (rxTime is not null) doc["rxTime"] = rxTime;       // nullable

        var relayedBy = ParseRelayedBy(GetObject(report, "reportedBy"));
        if (relayedBy is not null) doc["relayedBy"] = relayedBy;

        var secondaries = ParseSecondaries(GetArray(report, "secondaryObservations"));
        if (secondaries.Count > 0) doc["secondaries"] = secondaries;

        var observedBy = ParseObservedBy(GetArray(report, "observedBy"));
        if (observedBy.Count > 0) doc["observedBy"] = observedBy;

        var thirdParty = ParseThirdParty(GetArray(report, "thirdPartyObservations"));
        if (thirdParty.Count > 0) doc["thirdPartyObservations"] = thirdParty;

        var location = ParseLocation(GetObject(report, "location"));
        if (location is not null) doc["location"] = location;

        var sensors = ParseSensors(GetObject(report, "sensors"));
        if (sensors is not null) doc["sensors"] = sensors;

        var sensorEvents = JsonArrayToList(GetArray(report, "sensorEvents"));
        if (sensorEvents.Count > 0) doc["sensorEvents"] = sensorEvents;

        var networks = ParseNetworkObservations(GetObject(report, "networkObservations"));
        if (networks is not null) doc["networkObservations"] = networks;

        var ts = ParseTimeSeries(GetObject(report, "timeSeries"));
        if (ts is not null) doc["timeSeries"] = ts;

        return doc;
    }

    /// <summary>
    /// Compact per-device "latest state" projection — what you'd upsert into a
    /// <c>devices</c> collection on every report. Full history lives in
    /// <c>device_reports</c>.
    /// </summary>
    public static IDictionary<string, object?>? ProjectLatestState(IDictionary<string, object?>? doc)
    {
        if (doc is null || doc["device"] is not IDictionary<string, object?> device) return null;

        var state = new Dictionary<string, object?>(StringComparer.Ordinal)
        {
            ["_id"] = device["id"],
            ["displayId"] = device["displayId"],
            ["model"] = device["model"],
            ["labels"] = device.TryGetValue("labels", out var labels) ? labels : new List<string>(),
            ["lastReport"] = new Dictionary<string, object?>(StringComparer.Ordinal)
            {
                ["id"] = doc["_id"],
                ["time"] = doc["time"],
                ["role"] = doc["role"],
            },
            ["updatedAt"] = doc["time"],
        };

        if (device.TryGetValue("name", out var name) && name is not null) state["name"] = name;
        if (device.TryGetValue("firmware", out var fw) && fw is not null) state["firmware"] = fw;
        if (doc.TryGetValue("location", out var loc)) state["lastLocation"] = loc;
        if (doc.TryGetValue("sensors", out var sens)) state["lastSensors"] = sens;
        if (doc.TryGetValue("relayedBy", out var rb)) state["relayedBy"] = rb;
        return state;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static string DeriveRole(JsonElement report)
    {
        var relayedId = Str(report, "reportedBy", "device", "id");
        if (relayedId is not null) return "secondary";
        var secondaries = GetArray(report, "secondaryObservations");
        if (secondaries is not null && secondaries.Value.GetArrayLength() > 0) return "primary";
        return "standalone";
    }

    private static IDictionary<string, object?>? ParseDeviceIdentity(JsonElement? device)
    {
        if (device is not { ValueKind: JsonValueKind.Object } d) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal)
        {
            ["id"] = Str(d, "id"),
            ["displayId"] = Str(d, "displayId"),
            ["model"] = ParseModel(GetObject(d, "model")),
            ["labels"] = AsStringList(GetArray(d, "labels")),
        };
        var name = Str(d, "name");           if (name is not null) out_["name"] = name;             // nullable
        var fw = Str(d, "firmware");         if (fw is not null) out_["firmware"] = fw;             // nullable
        return out_;
    }

    private static IDictionary<string, object?>? ParseModel(JsonElement? model)
    {
        if (model is not { ValueKind: JsonValueKind.Object } m) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var key in new[] { "name", "family", "product", "version", "revision", "variant", "thirdParty" })
        {
            CopyIfPresent(m, out_, key);
        }
        return out_.Count == 0 ? null : out_;
    }

    private static IDictionary<string, object?>? ParseOwner(JsonElement? owner)
    {
        if (owner is not { ValueKind: JsonValueKind.Object } o) return null;
        return new Dictionary<string, object?>(StringComparer.Ordinal)
        {
            ["id"] = Str(o, "id"),
            ["name"] = Str(o, "name"),
        };
    }

    private static IDictionary<string, object?>? ParseRelayedBy(JsonElement? reportedBy)
    {
        if (reportedBy is not { ValueKind: JsonValueKind.Object } rb) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal);

        var device = ParseDeviceIdentity(GetObject(rb, "device"));
        if (device is not null) out_["device"] = device;

        if (GetObject(rb, "report") is { ValueKind: JsonValueKind.Object } rep)
        {
            out_["reportId"] = Str(rep, "id");                     // nullable
            var rt = Str(rep, "time"); if (rt is not null) out_["reportTime"] = rt;
        }
        if (GetObject(rb, "app") is { ValueKind: JsonValueKind.Object } app)
        {
            out_["app"] = JsonElementToObject(app);
        }
        if (TryGetInt(rb, "rssi", out var rssi)) out_["rssi"] = rssi;

        return out_.Count == 0 ? null : out_;
    }

    private static IDictionary<string, object?>? ParseLocation(JsonElement? location)
    {
        if (location is not { ValueKind: JsonValueKind.Object } loc) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var key in new[] { "type", "time", "summary" }) CopyIfPresent(loc, out_, key);

        if (GetObject(loc, "global") is { ValueKind: JsonValueKind.Object } global)
        {
            var g = new Dictionary<string, object?>(StringComparer.Ordinal);
            foreach (var k in new[] { "lat", "lon", "cep", "address" }) CopyIfPresent(global, g, k);
            if (g.Count > 0) out_["global"] = g;
        }
        if (GetObject(loc, "site") is { ValueKind: JsonValueKind.Object } site)
        {
            var s = new Dictionary<string, object?>(StringComparer.Ordinal);
            foreach (var k in new[] { "id", "name", "level", "x", "y", "z", "address", "cep", "rooms" })
            {
                CopyIfPresent(site, s, k);
            }
            if (s.Count > 0) out_["site"] = s;
        }
        if (GetArray(loc, "zones") is { ValueKind: JsonValueKind.Array } zones && zones.GetArrayLength() > 0)
        {
            var list = new List<object?>();
            foreach (var z in zones.EnumerateArray())
            {
                var zone = new Dictionary<string, object?>(StringComparer.Ordinal);
                CopyIfPresent(z, zone, "id");
                CopyIfPresent(z, zone, "name");
                if (zone.Count > 0) list.Add(zone);
            }
            if (list.Count > 0) out_["zones"] = list;
        }
        return out_.Count == 0 ? null : out_;
    }

    private static IDictionary<string, object?>? ParseSensors(JsonElement? sensors)
    {
        if (sensors is not { ValueKind: JsonValueKind.Object } s) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal);
        CopyIfPresent(s, out_, "time");

        if (GetObject(s, "battery") is { ValueKind: JsonValueKind.Object } battery)
        {
            var b = new Dictionary<string, object?>(StringComparer.Ordinal);
            foreach (var k in new[] { "level", "state", "voltage", "charging" }) CopyIfPresent(battery, b, k);
            if (b.Count > 0) out_["battery"] = b;
        }

        var env = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var k in new[] {
            "temperature", "extremeTemperature", "humidity", "atmosphericPressure",
            "lightLevel", "movement", "externalPower", "cellSignal" })
        {
            CopyIfPresent(s, env, k);
        }
        if (env.Count > 0) out_["environment"] = env;

        var orientation = ParseVector(GetObject(s, "orientation"));
        if (orientation is not null) out_["orientation"] = orientation;
        var vibration = ParseVector(GetObject(s, "vibration"));
        if (vibration is not null) out_["vibration"] = vibration;

        if (GetObject(s, "securitySwitch") is { ValueKind: JsonValueKind.Object } sec)
        {
            var ss = new Dictionary<string, object?>(StringComparer.Ordinal);
            foreach (var k in new[] { "state", "activations", "lastOpened", "lastClosed" }) CopyIfPresent(sec, ss, k);
            if (ss.Count > 0) out_["securitySwitch"] = ss;
        }
        if (GetObject(s, "sterilisation") is { ValueKind: JsonValueKind.Object } ster)
        {
            var st = new Dictionary<string, object?>(StringComparer.Ordinal);
            foreach (var k in new[] { "cycleCount", "cycleCountReadAt", "uptimeInSeconds", "uptimeReadAt" })
            {
                CopyIfPresent(ster, st, k);
            }
            if (st.Count > 0) out_["sterilisation"] = st;
        }

        return out_.Count == 0 ? null : out_;
    }

    private static IDictionary<string, object?>? ParseVector(JsonElement? vec)
    {
        if (vec is not { ValueKind: JsonValueKind.Object } v) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var k in new[] { "x", "y", "z" }) CopyIfPresent(v, out_, k);
        return out_.Count == 0 ? null : out_;
    }

    private static List<IDictionary<string, object?>> ParseSecondaries(JsonElement? arr)
    {
        var list = new List<IDictionary<string, object?>>();
        if (arr is not { ValueKind: JsonValueKind.Array } a) return list;
        foreach (var obs in a.EnumerateArray())
        {
            var entry = new Dictionary<string, object?>(StringComparer.Ordinal)
            {
                ["id"] = Str(obs, "id"),
                ["displayId"] = Str(obs, "displayId"),
                ["model"] = ParseModel(GetObject(obs, "model")),
                ["reportType"] = Str(obs, "reportType"),
            };
            foreach (var k in new[] { "name", "reportEventId", "reportId", "rssi", "isPaired" })
            {
                CopyIfPresent(obs, entry, k);
            }
            list.Add(entry);
        }
        return list;
    }

    private static List<IDictionary<string, object?>> ParseObservedBy(JsonElement? arr)
    {
        var list = new List<IDictionary<string, object?>>();
        if (arr is not { ValueKind: JsonValueKind.Array } a) return list;
        foreach (var obs in a.EnumerateArray())
        {
            var entry = new Dictionary<string, object?>(StringComparer.Ordinal)
            {
                ["id"] = Str(obs, "id"),
                ["displayId"] = Str(obs, "displayId"),
                ["model"] = ParseModel(GetObject(obs, "model")),
            };
            foreach (var k in new[] { "name", "reportId", "reportTime", "rssi" }) CopyIfPresent(obs, entry, k);
            list.Add(entry);
        }
        return list;
    }

    private static List<IDictionary<string, object?>> ParseThirdParty(JsonElement? arr)
    {
        var list = new List<IDictionary<string, object?>>();
        if (arr is not { ValueKind: JsonValueKind.Array } a) return list;
        foreach (var obs in a.EnumerateArray())
        {
            var type = Str(obs, "type");
            var entry = new Dictionary<string, object?>(StringComparer.Ordinal)
            {
                ["type"] = type,
                ["id"] = Str(obs, "id"),
                ["displayId"] = Str(obs, "displayId"),
            };
            switch (type)
            {
                case "reelable":
                    CopyIfPresent(obs, entry, "temperature");
                    break;
                case "chorus":
                    foreach (var k in new[] { "temperature", "sequenceNumber", "batteryLevel", "rssi", "tamper" })
                        CopyIfPresent(obs, entry, k);
                    break;
                case "cableSeal":
                    foreach (var k in new[] {
                        "state", "open", "highTemperature", "lowBattery",
                        "temperature", "batteryLevel", "counter", "rssi" })
                    {
                        CopyIfPresent(obs, entry, k);
                    }
                    break;
                default:
                    entry["raw"] = JsonElementToObject(obs);     // forward-compat
                    break;
            }
            list.Add(entry);
        }
        return list;
    }

    private static IDictionary<string, object?>? ParseNetworkObservations(JsonElement? networks)
    {
        if (networks is not { ValueKind: JsonValueKind.Object } n) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var k in new[] { "wifi", "cells", "lteNeighbourCells" })
        {
            if (GetArray(n, k) is { ValueKind: JsonValueKind.Array } arr && arr.GetArrayLength() > 0)
            {
                out_[k] = JsonArrayToList(arr);
            }
        }
        return out_.Count == 0 ? null : out_;
    }

    private static IDictionary<string, object?>? ParseTimeSeries(JsonElement? ts)
    {
        if (ts is not { ValueKind: JsonValueKind.Object } t) return null;
        var out_ = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var prop in t.EnumerateObject())
        {
            if (prop.Value.ValueKind == JsonValueKind.Array && prop.Value.GetArrayLength() > 0)
            {
                out_[prop.Name] = JsonArrayToList(prop.Value);
            }
        }
        return out_.Count == 0 ? null : out_;
    }

    // ── primitive helpers ──────────────────────────────────────────────────

    private static string? Str(JsonElement e, params string[] path)
    {
        foreach (var p in path)
        {
            if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty(p, out var next)) return null;
            e = next;
        }
        return e.ValueKind == JsonValueKind.String ? e.GetString() : null;
    }

    private static JsonElement? GetObject(JsonElement parent, string field)
    {
        if (parent.ValueKind != JsonValueKind.Object) return null;
        if (!parent.TryGetProperty(field, out var v)) return null;
        return v.ValueKind == JsonValueKind.Object ? v : null;
    }

    private static JsonElement? GetArray(JsonElement parent, string field)
    {
        if (parent.ValueKind != JsonValueKind.Object) return null;
        if (!parent.TryGetProperty(field, out var v)) return null;
        return v.ValueKind == JsonValueKind.Array ? v : null;
    }

    private static bool TryGetInt(JsonElement parent, string field, out int value)
    {
        value = 0;
        return parent.ValueKind == JsonValueKind.Object
               && parent.TryGetProperty(field, out var v)
               && v.ValueKind == JsonValueKind.Number
               && v.TryGetInt32(out value);
    }

    private static void CopyIfPresent(JsonElement src, IDictionary<string, object?> dst, string field)
    {
        if (src.ValueKind != JsonValueKind.Object) return;
        if (!src.TryGetProperty(field, out var v) || v.ValueKind == JsonValueKind.Null) return;
        dst[field] = JsonElementToObject(v);
    }

    private static List<string> AsStringList(JsonElement? arr)
    {
        var list = new List<string>();
        if (arr is not { ValueKind: JsonValueKind.Array } a) return list;
        foreach (var el in a.EnumerateArray())
        {
            if (el.ValueKind == JsonValueKind.String) list.Add(el.GetString()!);
        }
        return list;
    }

    private static List<object?> JsonArrayToList(JsonElement? arr)
    {
        var list = new List<object?>();
        if (arr is not { ValueKind: JsonValueKind.Array } a) return list;
        foreach (var el in a.EnumerateArray()) list.Add(JsonElementToObject(el));
        return list;
    }

    private static object? JsonElementToObject(JsonElement element)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                var dict = new Dictionary<string, object?>(StringComparer.Ordinal);
                foreach (var prop in element.EnumerateObject()) dict[prop.Name] = JsonElementToObject(prop.Value);
                return dict;
            case JsonValueKind.Array:
                return JsonArrayToList(element);
            case JsonValueKind.String: return element.GetString();
            case JsonValueKind.Number:
                if (element.TryGetInt32(out var i)) return i;
                if (element.TryGetInt64(out var l)) return l;
                return element.GetDouble();
            case JsonValueKind.True: return true;
            case JsonValueKind.False: return false;
            default: return null;
        }
    }
}
