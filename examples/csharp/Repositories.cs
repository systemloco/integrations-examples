using System.Text.Json;

namespace LocoAware.Webhook;

/// <summary>
/// Stand-in repositories. TODO: replace with EF Core, Dapper, or your ORM.
/// </summary>
public class ShipmentsRepository
{
    private readonly ILogger<ShipmentsRepository> _log;
    public ShipmentsRepository(ILogger<ShipmentsRepository> log) => _log = log;

    public virtual Task AppendEventAsync(string shipmentId, JsonElement @event)
    {
        var id = @event.TryGetProperty("id", out var e) ? e.GetString() : "(shipment message)";
        _log.LogInformation("append event {EventId} → shipment {ShipmentId}", id, shipmentId);
        return Task.CompletedTask;
    }

    /// <summary>
    /// Return the shipment ID associated with EITHER the device's name (which
    /// may hold a shipment ID or name) OR the device's id (if the device is
    /// currently assigned to an active shipment).
    ///
    /// Real implementation — one indexed SQL statement, cached aggressively:
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
        _log.LogInformation("lookup shipment by deviceName={DeviceName} OR deviceId={DeviceId}",
            deviceName, deviceId);
        return Task.FromResult<string?>(null);
    }

    public virtual Task<string?> FindIdByAssetNameOrIdAsync(string? assetName, string? assetId)
    {
        _log.LogInformation("lookup shipment by assetName={AssetName} OR assetId={AssetId}",
            assetName, assetId);
        return Task.FromResult<string?>(null);
    }
}

public class DevicesRepository
{
    private readonly ILogger<DevicesRepository> _log;
    public DevicesRepository(ILogger<DevicesRepository> log) => _log = log;

    public virtual Task<bool> ExistsAsync(string deviceId) => Task.FromResult(!string.IsNullOrEmpty(deviceId));

    public virtual Task UpdateLatestAsync(string deviceId, JsonElement @event)
    {
        _log.LogInformation("update latest for device {DeviceId}", deviceId);
        return Task.CompletedTask;
    }
}

public class AssetsRepository
{
    private readonly ILogger<AssetsRepository> _log;
    public AssetsRepository(ILogger<AssetsRepository> log) => _log = log;

    public virtual Task<bool> ExistsAsync(string assetId) => Task.FromResult(!string.IsNullOrEmpty(assetId));

    public virtual Task UpdateLatestAsync(string assetId, JsonElement @event)
    {
        _log.LogInformation("update latest for asset {AssetId}", assetId);
        return Task.CompletedTask;
    }
}
