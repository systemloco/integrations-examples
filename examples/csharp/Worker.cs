using System.Text.Json;

namespace LocoAware.Webhook;

/// <summary>
/// Drains messages the webhook endpoints enqueued via <see cref="QueueClient"/>.
/// The <see cref="QueueClient"/> stand-in is for illustration only — in
/// production this lives in a separate process subscribed to your real broker
/// (SQS, Service Bus, Kafka, RabbitMQ, …).
///
/// <para>For each device report, four writes happen against the document store:
/// <list type="number">
///   <item><description>Insert the parsed document into the immutable <c>device_reports</c> history.</description></item>
///   <item><description>If a shipment binds this device or its primary, append the document to it.</description></item>
///   <item><description>Upsert the reporting device's "latest state" in <c>devices</c>.</description></item>
///   <item><description>For primaries only: presence-touch each paired secondary. The tag's own report
///   is authoritative for its sensors — that touch only updates <c>lastSeenBy</c>.</description></item>
/// </list></para>
/// </summary>
public class Worker : BackgroundService
{
    private readonly QueueClient _queue;
    private readonly DeviceReportsRepository _deviceReports;
    private readonly ShipmentsRepository _shipments;
    private readonly DevicesRepository _devices;
    private readonly ILogger<Worker> _log;

    public Worker(
        QueueClient queue,
        DeviceReportsRepository deviceReports,
        ShipmentsRepository shipments,
        DevicesRepository devices,
        ILogger<Worker> log)
    {
        _queue = queue;
        _deviceReports = deviceReports;
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
                        await HandleDeviceReportAsync(doc.RootElement);
                        break;
                    case "locoaware.device-events":
                        await HandleDeviceEventAsync(doc.RootElement);
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

    public async Task HandleDeviceReportAsync(JsonElement report)
    {
        var doc = DeviceReportParser.Parse(report);
        if (doc is null) return;

        // 1. Immutable history.
        await _deviceReports.InsertAsync(doc);

        // 2. Shipment association — primary lookup, then relay fallback for tags.
        var shipmentId = await FindShipmentForReportAsync(doc);
        if (shipmentId is not null)
        {
            await _shipments.AppendEventAsync(shipmentId, doc);
        }

        // 3. Per-device latest state.
        var deviceId = DeviceIdOf(doc);
        if (deviceId is not null && await _devices.ExistsAsync(deviceId))
        {
            var state = DeviceReportParser.ProjectLatestState(doc);
            if (state is not null) await _devices.UpsertLatestAsync(deviceId, state);
        }

        // 4. Primary-side presence touches for paired secondaries.
        if (doc.TryGetValue("role", out var role) && role is "primary"
            && doc.TryGetValue("secondaries", out var secsObj)
            && secsObj is IEnumerable<IDictionary<string, object?>> secondaries)
        {
            foreach (var obs in secondaries)
            {
                var secondaryId = obs.TryGetValue("id", out var idObj) ? idObj as string : null;
                if (secondaryId is null || !await _devices.ExistsAsync(secondaryId)) continue;

                int? rssi = obs.TryGetValue("rssi", out var rObj) && rObj is int rInt ? rInt : null;
                var reportType = obs.TryGetValue("reportType", out var rtObj) ? rtObj as string : null;
                var time = doc.TryGetValue("time", out var tObj) ? tObj as string : null;

                await _devices.TouchObservationAsync(secondaryId, deviceId, rssi, reportType, time);
            }
        }
    }

    public async Task HandleDeviceEventAsync(JsonElement @event)
    {
        var doc = DeviceReportParser.Parse(@event);
        if (doc is null) return;

        var shipmentId = await FindShipmentForReportAsync(doc);
        if (shipmentId is not null)
        {
            await _shipments.AppendEventAsync(shipmentId, doc);
        }

        var deviceId = DeviceIdOf(doc);
        if (deviceId is not null && await _devices.ExistsAsync(deviceId))
        {
            var state = DeviceReportParser.ProjectLatestState(doc);
            if (state is not null) await _devices.UpsertLatestAsync(deviceId, state);
        }
    }

    /// <summary>
    /// Shipment lookup with a fallback to the relay/primary device. A
    /// secondary's (BLE LocoTag) report carries the primary that relayed it
    /// in <c>relayedBy.device</c>. Tags typically don't have a shipment
    /// binding of their own — falling back to the primary attaches the tag's
    /// data to the same shipment as the gateway it lives on.
    /// </summary>
    private async Task<string?> FindShipmentForReportAsync(IDictionary<string, object?> doc)
    {
        var device = doc.TryGetValue("device", out var devObj) ? devObj as IDictionary<string, object?> : null;
        var deviceName = device?.TryGetValue("name", out var n) == true ? n as string : null;
        var deviceId = device?.TryGetValue("id", out var i) == true ? i as string : null;

        var shipmentId = await _shipments.FindIdByDeviceNameOrIdAsync(deviceName, deviceId);
        if (shipmentId is not null) return shipmentId;

        var relayedBy = doc.TryGetValue("relayedBy", out var rb) ? rb as IDictionary<string, object?> : null;
        var relayDevice = relayedBy?.TryGetValue("device", out var rd) == true ? rd as IDictionary<string, object?> : null;
        var relayName = relayDevice?.TryGetValue("name", out var rn) == true ? rn as string : null;
        var relayId = relayDevice?.TryGetValue("id", out var ri) == true ? ri as string : null;
        if (relayName is not null || relayId is not null)
        {
            return await _shipments.FindIdByDeviceNameOrIdAsync(relayName, relayId);
        }
        return null;
    }

    private static string? DeviceIdOf(IDictionary<string, object?> doc)
    {
        if (doc.TryGetValue("device", out var devObj) && devObj is IDictionary<string, object?> device
            && device.TryGetValue("id", out var idObj))
        {
            return idObj as string;
        }
        return null;
    }
}
