using System.Collections.Concurrent;
using System.Text.Json;

namespace LocoAware.Webhook;

/// <summary>
/// In-memory stand-ins for a document store. The shape mirrors what you'd
/// push into MongoDB / DocumentDB / CosmosDB / Couchbase / Firestore. The
/// point of the examples is to show the SHAPE of the documents — the actual
/// driver is one swap away.
///
/// <para>Production replacements:
/// <list type="bullet">
///   <item><description>MongoDB:    <c>MongoDB.Driver</c> — <c>collection.UpdateOneAsync(filter, update, new UpdateOptions { IsUpsert = true })</c></description></item>
///   <item><description>CosmosDB:   <c>Microsoft.Azure.Cosmos</c> — <c>container.UpsertItemAsync(doc)</c></description></item>
///   <item><description>DynamoDB:   <c>AWSSDK.DynamoDBv2</c></description></item>
///   <item><description>Couchbase:  <c>Couchbase.Extensions.DependencyInjection</c></description></item>
///   <item><description>Firestore:  <c>Google.Cloud.Firestore</c></description></item>
/// </list>
/// Tables and schema migrations are explicitly out of scope here.</para>
/// </summary>
public class DeviceReportsRepository
{
    private readonly ILogger<DeviceReportsRepository> _log;
    private readonly ConcurrentDictionary<string, IDictionary<string, object?>> _store = new();

    public DeviceReportsRepository(ILogger<DeviceReportsRepository> log) => _log = log;

    public virtual Task InsertAsync(IDictionary<string, object?> doc)
    {
        if (doc.TryGetValue("_id", out var idObj) && idObj is string id && !string.IsNullOrEmpty(id))
        {
            // In production: await collection.InsertOneAsync(doc) — keyed by
            // report id so re-deliveries are idempotent.
            _store[id] = doc;
            _log.LogInformation("insert device_report {Id} (role={Role})", id, doc.TryGetValue("role", out var r) ? r : "unknown");
        }
        return Task.CompletedTask;
    }

    public virtual IDictionary<string, object?>? Get(string id) => _store.TryGetValue(id, out var d) ? d : null;
    public virtual IReadOnlyCollection<IDictionary<string, object?>> All() => _store.Values.ToList();
    public virtual void Clear() => _store.Clear();
}

public class ShipmentsRepository
{
    private readonly ILogger<ShipmentsRepository> _log;
    private readonly ConcurrentDictionary<string, IDictionary<string, object?>> _store = new();

    public ShipmentsRepository(ILogger<ShipmentsRepository> log) => _log = log;

    public virtual Task AppendEventAsync(string shipmentId, JsonElement @event)
        => AppendEventAsync(shipmentId, (object)@event);

    public virtual Task AppendEventAsync(string shipmentId, object @event)
    {
        if (string.IsNullOrEmpty(shipmentId)) return Task.CompletedTask;
        _store.AddOrUpdate(
            shipmentId,
            _ =>
            {
                var doc = NewDoc(shipmentId);
                ((List<object?>)doc["events"]!).Add(@event);
                return doc;
            },
            (_, existing) =>
            {
                ((List<object?>)existing["events"]!).Add(@event);
                return existing;
            });
        _log.LogInformation("append event → shipment {ShipmentId}", shipmentId);
        return Task.CompletedTask;
    }

    /// <summary>
    /// Return the shipment ID associated with EITHER the device's name (which
    /// may hold a shipment ID or name) OR the device's id (if the device is
    /// currently assigned to an active shipment). The worker's hottest read
    /// — cache aggressively in production.
    ///
    /// Real implementation — one indexed SQL statement:
    /// <code>
    ///   SELECT id FROM shipments
    ///   WHERE status IN ('ready', 'inProgress')
    ///     AND (name = @name OR id = @name OR id IN (
    ///       SELECT shipment_id FROM shipment_devices WHERE device_id = @id
    ///     ))
    ///   LIMIT 1;
    /// </code>
    /// </summary>
    public virtual Task<string?> FindIdByDeviceNameOrIdAsync(string? deviceName, string? deviceId)
    {
        _log.LogInformation("lookup shipment by deviceName={DeviceName} OR deviceId={DeviceId}", deviceName, deviceId);
        foreach (var kv in _store)
        {
            if (deviceName is not null && ListContains(kv.Value, "boundDeviceNames", deviceName)) return Task.FromResult<string?>(kv.Key);
            if (deviceId is not null && ListContains(kv.Value, "boundDeviceIds", deviceId)) return Task.FromResult<string?>(kv.Key);
        }
        return Task.FromResult<string?>(null);
    }

    public virtual Task<string?> FindIdByAssetNameOrIdAsync(string? assetName, string? assetId)
    {
        _log.LogInformation("lookup shipment by assetName={AssetName} OR assetId={AssetId}", assetName, assetId);
        foreach (var kv in _store)
        {
            if (assetName is not null && ListContains(kv.Value, "boundAssetNames", assetName)) return Task.FromResult<string?>(kv.Key);
            if (assetId is not null && ListContains(kv.Value, "boundAssetIds", assetId)) return Task.FromResult<string?>(kv.Key);
        }
        return Task.FromResult<string?>(null);
    }

    public virtual void Seed(string id, IDictionary<string, object?> overlay)
    {
        var doc = NewDoc(id);
        foreach (var kv in overlay) doc[kv.Key] = kv.Value;
        _store[id] = doc;
    }

    public virtual IDictionary<string, object?>? Get(string id) => _store.TryGetValue(id, out var d) ? d : null;
    public virtual void Clear() => _store.Clear();

    private static IDictionary<string, object?> NewDoc(string id) => new Dictionary<string, object?>(StringComparer.Ordinal)
    {
        ["_id"] = id,
        ["events"] = new List<object?>(),
    };

    private static bool ListContains(IDictionary<string, object?> doc, string key, string value)
        => doc.TryGetValue(key, out var v)
           && v is System.Collections.IEnumerable list
           && list.Cast<object?>().Any(x => x is string s && s == value);
}

public class DevicesRepository
{
    private readonly ILogger<DevicesRepository> _log;
    private readonly ConcurrentDictionary<string, IDictionary<string, object?>> _store = new();

    public DevicesRepository(ILogger<DevicesRepository> log) => _log = log;

    public virtual Task<bool> ExistsAsync(string deviceId)
    {
        // Real implementations might keep a known-device cache, or do
        // collection.FindAsync(filter). For the stand-in we accept any
        // non-empty id so the worker is exercised.
        return Task.FromResult(!string.IsNullOrEmpty(deviceId));
    }

    public virtual Task UpsertLatestAsync(string deviceId, IDictionary<string, object?> patch)
    {
        if (string.IsNullOrEmpty(deviceId)) return Task.CompletedTask;
        _store.AddOrUpdate(
            deviceId,
            _ =>
            {
                var doc = new Dictionary<string, object?>(patch, StringComparer.Ordinal) { ["_id"] = deviceId };
                return doc;
            },
            (_, existing) =>
            {
                foreach (var kv in patch) existing[kv.Key] = kv.Value;
                existing["_id"] = deviceId;
                return existing;
            });
        // Production:
        //   await collection.UpdateOneAsync(
        //     Builders<BsonDocument>.Filter.Eq("_id", deviceId),
        //     Builders<BsonDocument>.Update.Set(...),
        //     new UpdateOptions { IsUpsert = true });
        _log.LogInformation("upsert device {DeviceId}", deviceId);
        return Task.CompletedTask;
    }

    /// <summary>
    /// Presence-only touch — a secondary's primary saw it during this
    /// reporting window. The tag's AUTHORITATIVE sensor data lands in the
    /// tag's own standalone report; do not overwrite sensor fields here.
    /// </summary>
    public virtual Task TouchObservationAsync(string secondaryId, string? primaryDeviceId, int? rssi, string? reportType, string? time)
    {
        if (string.IsNullOrEmpty(secondaryId)) return Task.CompletedTask;
        var lastSeenBy = new Dictionary<string, object?>(StringComparer.Ordinal)
        {
            ["primaryDeviceId"] = primaryDeviceId,
        };
        if (rssi.HasValue) lastSeenBy["rssi"] = rssi.Value;          // nullable
        if (reportType is not null) lastSeenBy["reportType"] = reportType;
        if (time is not null) lastSeenBy["time"] = time;

        _store.AddOrUpdate(
            secondaryId,
            _ => new Dictionary<string, object?>(StringComparer.Ordinal)
            {
                ["_id"] = secondaryId,
                ["lastSeenBy"] = lastSeenBy,
            },
            (_, existing) =>
            {
                existing["lastSeenBy"] = lastSeenBy;
                existing["_id"] = secondaryId;
                return existing;
            });
        _log.LogInformation("touch device {SecondaryId} seen by primary {PrimaryId}", secondaryId, primaryDeviceId);
        return Task.CompletedTask;
    }

    /// <summary>Back-compat alias for callers handing in a raw envelope.</summary>
    public virtual Task UpdateLatestAsync(string deviceId, JsonElement @event)
    {
        var patch = new Dictionary<string, object?>(StringComparer.Ordinal) { ["lastEvent"] = @event };
        return UpsertLatestAsync(deviceId, patch);
    }

    public virtual IDictionary<string, object?>? Get(string id) => _store.TryGetValue(id, out var d) ? d : null;
    public virtual void Clear() => _store.Clear();
}

public class AssetsRepository
{
    private readonly ILogger<AssetsRepository> _log;
    private readonly ConcurrentDictionary<string, IDictionary<string, object?>> _store = new();

    public AssetsRepository(ILogger<AssetsRepository> log) => _log = log;

    public virtual Task<bool> ExistsAsync(string assetId) => Task.FromResult(!string.IsNullOrEmpty(assetId));

    public virtual Task UpdateLatestAsync(string assetId, JsonElement @event)
    {
        if (string.IsNullOrEmpty(assetId)) return Task.CompletedTask;
        _store[assetId] = new Dictionary<string, object?>(StringComparer.Ordinal)
        {
            ["_id"] = assetId,
            ["lastEvent"] = @event,
        };
        _log.LogInformation("upsert asset {AssetId}", assetId);
        return Task.CompletedTask;
    }

    public virtual IDictionary<string, object?>? Get(string id) => _store.TryGetValue(id, out var d) ? d : null;
    public virtual void Clear() => _store.Clear();
}
