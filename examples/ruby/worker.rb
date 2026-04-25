require_relative 'queue_client'
require_relative 'repositories'

# Drains messages the Sinatra app enqueued via QueueClient.
# In production this lives in a separate long-running process (Sidekiq,
# Shoryuken, etc.) subscribed to your real broker.

def handle_device_report(report)
  device = report['device'] || {}

  if (shipment_id = Shipments.find_id_by_device_name_or_id(device['name'], device['id']))
    Shipments.append_event(shipment_id, report)
  end

  if (device_id = device['id']) && Devices.exists?(device_id)
    Devices.update_latest(device_id, report)
  end
end

def handle_device_event(event)
  device = event['device'] || {}

  if (shipment_id = Shipments.find_id_by_device_name_or_id(device['name'], device['id']))
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
