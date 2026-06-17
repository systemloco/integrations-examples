require_relative 'queue_client'
require_relative 'repositories'

# Drains messages the Sinatra app enqueued via QueueClient.
# In production this lives in a separate long-running process (Sidekiq,
# Shoryuken, etc.) subscribed to your real broker.

# Shipment lookup with a fallback to the relay/primary device.
#
# A secondary's (BLE tag's) report carries the primary that relayed it in
# `reportedBy.device`. Tags typically don't have a shipment binding of their
# own — falling back to the primary attaches the tag's data to the same
# shipment as the gateway it lives on.
def find_shipment_for_report(report)
  device = report['device'] || {}
  shipment_id = Shipments.find_id_by_device_name_or_id(device['name'], device['id'])
  return shipment_id if shipment_id

  relay = (report.dig('reportedBy', 'device')) || {}
  return nil unless relay['name'] || relay['id']

  Shipments.find_id_by_device_name_or_id(relay['name'], relay['id'])
end

def handle_device_report(report)
  device = report['device'] || {}

  if (shipment_id = find_shipment_for_report(report))
    Shipments.append_event(shipment_id, report)
  end

  if (device_id = device['id']) && Devices.exists?(device_id)
    Devices.update_latest(device_id, report)
  end

  # Touch each observed secondary (paired tag) — presence only. The tag's
  # authoritative sensor data arrives in its own separate report.
  (report['secondaryObservations'] || []).each do |observation|
    secondary_id = observation['id']
    next unless secondary_id && Devices.exists?(secondary_id)

    Devices.update_latest(secondary_id, observation)
  end
end

def handle_device_event(event)
  device = event['device'] || {}

  if (shipment_id = find_shipment_for_report(event))
    Shipments.append_event(shipment_id, event)
  end

  if (device_id = device['id']) && Devices.exists?(device_id)
    Devices.update_latest(device_id, event)
  end
end

QueueClient.subscribe('locoaware.device-reports') { |r| handle_device_report(r) }
QueueClient.subscribe('locoaware.device-events')  { |e| handle_device_event(e) }

warn '[worker] subscribed to locoaware.device-reports and locoaware.device-events'
sleep
