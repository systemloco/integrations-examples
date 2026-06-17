using System.Text.Json;

namespace LocoAware.Webhook;

/// <summary>
/// Drains messages the webhook endpoints enqueued via <see cref="QueueClient"/>.
/// In production this lives in a separate process subscribed to your real broker.
/// </summary>
public class Worker : BackgroundService
{
    private readonly QueueClient _queue;
    private readonly ShipmentsRepository _shipments;
    private readonly DevicesRepository _devices;
    private readonly ILogger<Worker> _log;

    public Worker(
        QueueClient queue,
        ShipmentsRepository shipments,
        DevicesRepository devices,
        ILogger<Worker> log)
    {
        _queue = queue;
        _shipments = shipments;
        _devices = devices;
        _log = log;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await foreach (var envelope in _queue.Reader.ReadAllAsync(stoppingToken))
        {
            try
            {
                var doc = JsonDocument.Parse(envelope.Body);
                switch (envelope.Topic)
                {
                    case "locoaware.device-reports":
                        await HandleDeviceReport(doc.RootElement);
                        break;
                    case "locoaware.device-events":
                        await HandleDeviceEvent(doc.RootElement);
                        break;
                    default:
                        _log.LogWarning("ignoring unknown topic {Topic}", envelope.Topic);
                        break;
                }
            }
            catch (Exception ex)
            {
                _log.LogError(ex, "worker failed on {Topic} — broker would redeliver", envelope.Topic);
                // With a real broker: don't ack → message returns to the queue.
            }
        }
    }

    private async Task HandleDeviceReport(JsonElement report)
    {
        var deviceId = Str(report, "device", "id");

        var shipmentId = await FindShipmentForReportAsync(report);
        if (shipmentId is not null)
        {
            await _shipments.AppendEventAsync(shipmentId, report);
        }

        if (deviceId is not null && await _devices.ExistsAsync(deviceId))
        {
            await _devices.UpdateLatestAsync(deviceId, report);
        }

        // Touch each observed secondary (paired tag) — presence only. The tag's
        // authoritative sensor data arrives in its own separate report.
        if (report.ValueKind == JsonValueKind.Object
            && report.TryGetProperty("secondaryObservations", out var observations)
            && observations.ValueKind == JsonValueKind.Array)
        {
            foreach (var observation in observations.EnumerateArray())
            {
                var secondaryId = Str(observation, "id");
                if (secondaryId is not null && await _devices.ExistsAsync(secondaryId))
                {
                    await _devices.UpdateLatestAsync(secondaryId, observation);
                }
            }
        }
    }

    private async Task HandleDeviceEvent(JsonElement @event)
    {
        var deviceId = Str(@event, "device", "id");

        var shipmentId = await FindShipmentForReportAsync(@event);
        if (shipmentId is not null)
        {
            await _shipments.AppendEventAsync(shipmentId, @event);
        }

        if (deviceId is not null && await _devices.ExistsAsync(deviceId))
        {
            await _devices.UpdateLatestAsync(deviceId, @event);
        }
    }

    /// <summary>
    /// Shipment lookup with a fallback to the relay/primary device.
    ///
    /// A secondary's (BLE tag's) report carries the primary that relayed it in
    /// <c>reportedBy.device</c>. Tags typically don't have a shipment binding
    /// of their own — falling back to the primary attaches the tag's data to
    /// the same shipment as the gateway it lives on.
    /// </summary>
    private async Task<string?> FindShipmentForReportAsync(JsonElement report)
    {
        var deviceName = Str(report, "device", "name");
        var deviceId   = Str(report, "device", "id");
        var shipmentId = await _shipments.FindIdByDeviceNameOrIdAsync(deviceName, deviceId);
        if (shipmentId is not null) return shipmentId;

        var relayName = Str(report, "reportedBy", "device", "name");
        var relayId   = Str(report, "reportedBy", "device", "id");
        if (relayName is not null || relayId is not null)
        {
            return await _shipments.FindIdByDeviceNameOrIdAsync(relayName, relayId);
        }
        return null;
    }

    private static string? Str(JsonElement e, params string[] path)
    {
        foreach (var p in path)
        {
            if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty(p, out var next))
                return null;
            e = next;
        }
        return e.ValueKind == JsonValueKind.String ? e.GetString() : null;
    }
}
