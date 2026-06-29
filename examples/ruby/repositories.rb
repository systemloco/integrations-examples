# In-memory stand-in for a document store. The shape mirrors what you'd push
# into MongoDB / DocumentDB / CosmosDB / Firestore / Couchbase. The point of
# the examples is to show the SHAPE of the documents — the actual driver is
# one swap away.
#
# Production replacements:
#   - MongoDB     mongo gem        collection.update_one({_id:}, {'$set' => doc}, upsert: true)
#   - Mongoid     mongoid gem      Model.find_or_initialize_by(...).update(...)
#   - CosmosDB    azure-cosmos
#   - Firestore   google-cloud-firestore
#   - DynamoDB    aws-sdk-dynamodb
#   - Couchbase   couchbase
#
# Tables and schema migrations are out of scope here — see the integration
# guide for guidance on a relational shape.

module DocStore
  # One Hash per logical "collection", keyed by document _id.
  COLLECTIONS = {
    device_reports: {}, # immutable history (one doc per delivered webhook)
    devices: {},        # per-device latest-state (upserted)
    shipments: {},      # one doc per shipment (events appended)
    assets: {}          # per-asset latest-state (upserted)
  }.freeze

  module_function

  def reset_all
    COLLECTIONS.each_value(&:clear)
  end
end

# ─── Device reports — immutable history ─────────────────────────────────────
module DeviceReports
  module_function

  def insert(doc)
    return if doc.nil? || doc['_id'].nil?

    # Production: collection.insert_one(doc) — _id from the report is reused
    # so re-deliveries from the source are idempotent.
    DocStore::COLLECTIONS[:device_reports][doc['_id']] = doc
    warn "[repo] insert device_report #{doc['_id']} (role=#{doc['role'] || 'unknown'})"
  end

  def get(id)
    DocStore::COLLECTIONS[:device_reports][id]
  end

  def all
    DocStore::COLLECTIONS[:device_reports].values
  end
end

# ─── Devices — per-device latest state ──────────────────────────────────────
module Devices
  module_function

  def exists?(device_id)
    return false if device_id.nil? || device_id.to_s.empty?

    # Real implementations might keep a known-device cache, or do
    # `collection.find({_id: device_id}, projection: {_id: 1}).first`.
    # For the stand-in we accept any non-empty id so the worker is exercised.
    true
  end

  def upsert_latest(device_id, patch)
    return if device_id.nil? || device_id.to_s.empty?

    current = DocStore::COLLECTIONS[:devices][device_id] || { '_id' => device_id }
    DocStore::COLLECTIONS[:devices][device_id] = current.merge(patch).merge('_id' => device_id)

    # Production:
    #   collection.update_one({_id: device_id}, {'$set' => patch}, upsert: true)
    warn "[repo] upsert device #{device_id}"
  end

  # Presence-only touch: a secondary's primary saw it during a reporting
  # window. The tag's AUTHORITATIVE sensor data arrives in the tag's own
  # standalone report — do NOT overwrite sensor fields here.
  def touch_observation(secondary_id, primary_device_id:, rssi: nil, time: nil, report_type: nil)
    return if secondary_id.nil? || secondary_id.to_s.empty?

    last_seen_by = { 'primaryDeviceId' => primary_device_id }
    last_seen_by['rssi'] = rssi               unless rssi.nil?    # nullable
    last_seen_by['reportType'] = report_type  unless report_type.nil?
    last_seen_by['time'] = time               unless time.nil?

    current = DocStore::COLLECTIONS[:devices][secondary_id] || { '_id' => secondary_id }
    DocStore::COLLECTIONS[:devices][secondary_id] = current.merge('lastSeenBy' => last_seen_by)

    # Production:
    #   collection.update_one(
    #     {_id: secondary_id},
    #     {'$set' => {lastSeenBy: last_seen_by}},
    #     upsert: true
    #   )
    warn "[repo] touch device #{secondary_id} seen by primary #{primary_device_id}"
  end

  # Back-compat alias for the older shape — same behaviour as upsert_latest.
  def update_latest(device_id, event)
    upsert_latest(device_id, 'lastEvent' => event)
  end

  def get(id)
    DocStore::COLLECTIONS[:devices][id]
  end

  def all
    DocStore::COLLECTIONS[:devices].values
  end
end

# ─── Shipments — events appended ────────────────────────────────────────────
module Shipments
  module_function

  def append_event(shipment_id, event)
    return if shipment_id.nil?

    current = DocStore::COLLECTIONS[:shipments][shipment_id] || { '_id' => shipment_id, 'events' => [] }
    current['events'] << event
    DocStore::COLLECTIONS[:shipments][shipment_id] = current

    id = event.is_a?(Hash) ? (event['_id'] || event['id']) : '(message)'
    warn "[repo] append event #{id} → shipment #{shipment_id}"
  end

  # Return the shipment id associated with EITHER the device's name (which
  # often carries a shipment id/name) OR the device's id (if the device is
  # assigned to an active shipment). This is the worker's hottest read —
  # cache aggressively in production.
  #
  # Real implementation — one indexed SQL statement:
  #
  #   SELECT id FROM shipments
  #   WHERE status IN ('ready', 'inProgress')
  #     AND (name = $1 OR id = $1 OR id IN (
  #       SELECT shipment_id FROM shipment_devices WHERE device_id = $2
  #     ))
  #   LIMIT 1;
  def find_id_by_device_name_or_id(device_name, device_id)
    warn "[repo] lookup shipment by deviceName=#{device_name} OR deviceId=#{device_id}"
    DocStore::COLLECTIONS[:shipments].each do |id, doc|
      return id if device_name && Array(doc['boundDeviceNames']).include?(device_name)
      return id if device_id && Array(doc['boundDeviceIds']).include?(device_id)
    end
    nil
  end

  def find_id_by_asset_name_or_id(asset_name, asset_id)
    warn "[repo] lookup shipment by assetName=#{asset_name} OR assetId=#{asset_id}"
    DocStore::COLLECTIONS[:shipments].each do |id, doc|
      return id if asset_name && Array(doc['boundAssetNames']).include?(asset_name)
      return id if asset_id && Array(doc['boundAssetIds']).include?(asset_id)
    end
    nil
  end

  def seed(id, doc)
    DocStore::COLLECTIONS[:shipments][id] = { '_id' => id, 'events' => [] }.merge(doc)
  end

  def get(id)
    DocStore::COLLECTIONS[:shipments][id]
  end

  def all
    DocStore::COLLECTIONS[:shipments].values
  end
end

# ─── Assets — per-asset latest state ────────────────────────────────────────
module Assets
  module_function

  def exists?(asset_id)
    !asset_id.nil? && !asset_id.to_s.empty?
  end

  def update_latest(asset_id, event)
    return if asset_id.nil?

    current = DocStore::COLLECTIONS[:assets][asset_id] || { '_id' => asset_id }
    DocStore::COLLECTIONS[:assets][asset_id] = current.merge('lastEvent' => event, 'updatedAt' => event['time'])
    warn "[repo] upsert asset #{asset_id}"
  end

  def get(id)
    DocStore::COLLECTIONS[:assets][id]
  end

  def all
    DocStore::COLLECTIONS[:assets].values
  end
end
