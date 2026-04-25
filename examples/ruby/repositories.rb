# Stand-in repositories. TODO: replace with ActiveRecord / Sequel / ROM.

module Shipments
  module_function

  def append_event(shipment_id, event)
    id = event.is_a?(Hash) ? event['id'] : '(shipment message)'
    warn "[repo] append event #{id} → shipment #{shipment_id}"
  end

  # Return the shipment id associated with EITHER the device's name (which may
  # hold a shipment id or name) OR the device's id (if the device is assigned
  # to an active shipment).
  #
  # Real implementation — one indexed SQL statement, cached aggressively:
  #
  #   SELECT id FROM shipments
  #   WHERE status IN ('ready', 'inProgress')
  #     AND (name = $1 OR id = $1 OR id IN (
  #       SELECT shipment_id FROM shipment_devices WHERE device_id = $2
  #     ))
  #   LIMIT 1;
  def find_id_by_device_name_or_id(device_name, device_id)
    warn "[repo] lookup shipment by deviceName=#{device_name} OR deviceId=#{device_id}"
    nil
  end

  def find_id_by_asset_name_or_id(asset_name, asset_id)
    warn "[repo] lookup shipment by assetName=#{asset_name} OR assetId=#{asset_id}"
    nil
  end
end

module Devices
  module_function
  def exists?(device_id) = !device_id.nil? && !device_id.empty?
  def update_latest(device_id, _event) = warn("[repo] update latest for device #{device_id}")
end

module Assets
  module_function
  def exists?(asset_id) = !asset_id.nil? && !asset_id.empty?
  def update_latest(asset_id, _event) = warn("[repo] update latest for asset #{asset_id}")
end
