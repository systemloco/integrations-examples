require_relative 'queue_client'
require_relative 'repositories'
require_relative 'device_report_parser'

# Drains messages the Sinatra app enqueued via QueueClient.
# In production this lives in a separate long-running process (Sidekiq,
# Shoryuken, etc.) subscribed to your real broker. The in-memory QueueClient
# is for illustration only — wire your real broker into queue_client.rb.

module Worker
  module_function

  # Shipment lookup with a fallback to the relay/primary device.
  #
  # A secondary's (BLE LocoTag) report carries the primary that relayed it in
  # `reportedBy.device` (`relayedBy.device` after parsing). Tags typically
  # don't have a shipment binding of their own — falling back to the primary
  # attaches the tag's data to the same shipment as the gateway it lives on.
  def find_shipment_for_report(doc)
    device = doc['device'] || {}
    shipment_id = Shipments.find_id_by_device_name_or_id(device['name'], device['id'])
    return shipment_id if shipment_id

    relay = doc.dig('relayedBy', 'device') || {}
    return nil unless relay['name'] || relay['id']

    Shipments.find_id_by_device_name_or_id(relay['name'], relay['id'])
  end

  # Device report handler — four writes:
  #   1. Append to immutable `device_reports` history.
  #   2. Append to a shipment (if one binds this device or its primary).
  #   3. Upsert the reporting device's "latest state" in `devices`.
  #   4. For primaries only: presence-touch each paired secondary. Sensor data
  #      is NOT clobbered — the tag's standalone report is authoritative.
  def handle_device_report(report)
    doc = DeviceReportParser.parse(report)
    return if doc.nil?

    DeviceReports.insert(doc)

    if (shipment_id = find_shipment_for_report(doc))
      Shipments.append_event(shipment_id, doc)
    end

    device_id = doc.dig('device', 'id')
    if device_id && Devices.exists?(device_id)
      Devices.upsert_latest(device_id, DeviceReportParser.project_latest_state(doc))
    end

    if doc['role'] == 'primary' && doc['secondaries'].is_a?(Array)
      doc['secondaries'].each do |obs|
        next unless obs['id'] && Devices.exists?(obs['id'])

        Devices.touch_observation(
          obs['id'],
          primary_device_id: device_id,
          rssi: obs['rssi'],                # nullable
          report_type: obs['reportType'],
          time: doc['time']
        )
      end
    end
  end

  # Device event handler — same lookup, simpler write.
  def handle_device_event(event)
    doc = DeviceReportParser.parse(event)
    return if doc.nil?

    if (shipment_id = find_shipment_for_report(doc))
      Shipments.append_event(shipment_id, doc)
    end

    device_id = doc.dig('device', 'id')
    return unless device_id && Devices.exists?(device_id)

    Devices.upsert_latest(device_id, DeviceReportParser.project_latest_state(doc))
  end
end

# Only subscribe + sleep when this file is the entrypoint. Tests can require
# it without spinning up a queue listener.
if $PROGRAM_NAME == __FILE__
  QueueClient.subscribe('locoaware.device-reports') do |raw|
    payload = raw.is_a?(String) ? JSON.parse(raw) : raw
    Worker.handle_device_report(payload)
  end
  QueueClient.subscribe('locoaware.device-events') do |raw|
    payload = raw.is_a?(String) ? JSON.parse(raw) : raw
    Worker.handle_device_event(payload)
  end

  warn '[worker] subscribed to locoaware.device-reports and locoaware.device-events'
  sleep
end
