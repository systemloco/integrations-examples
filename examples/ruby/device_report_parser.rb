# Parse a Device Reports V2 webhook payload into a document-store-shaped record.
#
# The output mirrors what you'd insert into MongoDB / DocumentDB / CosmosDB /
# Couchbase — '_id' keyed to the report ID, with each documented subsection
# extracted into a named field. Tables and schema migrations are out of scope.
#
# ─── Null handling ──────────────────────────────────────────────────────────
# The feed OMITS null/absent fields entirely rather than sending them as
# `null` (see Device_Reports_V2_Integration_Guide.md §10). This parser keeps
# that convention: every optional field is only set on the output if the
# source field is present and non-nil. Optional outputs are flagged inline.
#
# ─── Primary / Secondary reporting ──────────────────────────────────────────
# LocoTrack hubs (primaries, a.k.a. "masters") relay BLE LocoTag (secondary,
# a.k.a. "slave") state. The same tag emits TWO feed messages per reporting
# window — one from the primary listing it in `secondaryObservations[]`, and
# one standalone from the tag with `reportedBy.device` pointing back at the
# primary. This parser tags each document with a `role`:
#
#   - "primary"    — this report came from a hub that observed secondaries
#   - "secondary"  — this report was relayed by a primary
#   - "standalone" — a self-contained device reporting itself
#
# The two messages link back together with:
#   primary_doc['secondaries'][i]['reportId'] == secondary_doc['_id']
#   secondary_doc['relayedBy']['reportId']    == primary_doc['_id']

module DeviceReportParser
  module_function

  def parse(report)
    return nil if report.nil?

    role = derive_role(report)

    doc = {
      '_id' => report['id'],
      'time' => report['time'],
      'role' => role,
      'device' => parse_device_identity(report['device']),
      'owner' => parse_owner(report['owner']),
      'reportReasons' => report['reportReasons'] || []
    }

    doc['rxTime'] = report['rxTime'] if present?(report['rxTime'])

    relayed = parse_relayed_by(report['reportedBy'])
    doc['relayedBy'] = relayed if relayed

    # Primary's view of paired secondaries — presence/RSSI only.
    if array_present?(report['secondaryObservations'])
      doc['secondaries'] = report['secondaryObservations'].map { |o| parse_secondary(o) }
    end

    if array_present?(report['observedBy'])
      doc['observedBy'] = report['observedBy'].map do |o|
        {
          'id' => o['id'],
          'displayId' => o['displayId'],
          'model' => parse_model(o['model'])
        }.merge(pick_present(o, %w[name reportId reportTime rssi]))
      end
    end

    if array_present?(report['thirdPartyObservations'])
      doc['thirdPartyObservations'] = report['thirdPartyObservations'].map { |o| parse_third_party(o) }
    end

    location = parse_location(report['location'])
    doc['location'] = location if location

    sensors = parse_sensors(report['sensors'])
    doc['sensors'] = sensors if sensors

    if array_present?(report['sensorEvents'])
      doc['sensorEvents'] = report['sensorEvents']
    end

    networks = parse_network_observations(report['networkObservations'])
    doc['networkObservations'] = networks if networks

    ts = parse_time_series(report['timeSeries'])
    doc['timeSeries'] = ts if ts

    doc
  end

  # A minimal projection for the per-device "latest state" collection. Only
  # the bits that change frequently and are useful for dashboards.
  def project_latest_state(doc)
    return nil if doc.nil? || doc['device'].nil?

    state = {
      '_id' => doc['device']['id'],
      'displayId' => doc['device']['displayId'],
      'model' => doc['device']['model'],
      'labels' => doc['device']['labels'] || [],
      'lastReport' => { 'id' => doc['_id'], 'time' => doc['time'], 'role' => doc['role'] },
      'updatedAt' => doc['time']
    }
    state['name'] = doc['device']['name']         if doc['device']['name']
    state['firmware'] = doc['device']['firmware'] if doc['device']['firmware']
    state['lastLocation'] = doc['location']       if doc['location']
    state['lastSensors'] = doc['sensors']         if doc['sensors']
    state['relayedBy'] = doc['relayedBy']         if doc['relayedBy']
    state
  end

  # ── helpers ───────────────────────────────────────────────────────────────

  def derive_role(report)
    relayed_id = report.dig('reportedBy', 'device', 'id')
    return 'secondary' if relayed_id

    secondaries = report['secondaryObservations']
    return 'primary' if secondaries.is_a?(Array) && !secondaries.empty?

    'standalone'
  end

  def parse_device_identity(device)
    return nil if device.nil?

    out = {
      'id' => device['id'],
      'displayId' => device['displayId'],
      'model' => parse_model(device['model']),
      'labels' => device['labels'] || []
    }
    out['name'] = device['name']         if present?(device['name'])         # nullable
    out['firmware'] = device['firmware'] if present?(device['firmware'])     # nullable
    out
  end

  def parse_model(model)
    return nil if model.nil?

    pick_present(model, %w[name family product version revision variant thirdParty])
  end

  def parse_owner(owner)
    return nil if owner.nil?

    { 'id' => owner['id'], 'name' => owner['name'] }
  end

  def parse_relayed_by(reported_by)
    return nil if reported_by.nil?

    out = {}
    out['device'] = parse_device_identity(reported_by['device']) if reported_by['device']
    if (rep = reported_by['report'])
      out['reportId'] = rep['id']                     # nullable
      out['reportTime'] = rep['time'] if present?(rep['time'])
    end
    out['app'] = reported_by['app']           if reported_by['app']           # nullable
    out['rssi'] = reported_by['rssi']         if present?(reported_by['rssi']) # nullable
    out.empty? ? nil : out
  end

  def parse_location(location)
    return nil if location.nil?

    out = {}
    %w[type time summary].each do |k|
      out[k] = location[k] if present?(location[k])
    end

    if location['global']
      g = pick_present(location['global'], %w[lat lon cep address])
      out['global'] = g unless g.empty?
    end
    if location['site']
      s = pick_present(location['site'], %w[id name level x y z address cep rooms])
      out['site'] = s unless s.empty?
    end
    if array_present?(location['zones'])
      out['zones'] = location['zones'].map { |z| pick_present(z, %w[id name]) }
    end

    out.empty? ? nil : out
  end

  def parse_sensors(sensors)
    return nil if sensors.nil?

    out = {}
    out['time'] = sensors['time'] if present?(sensors['time'])

    battery = pick_present(sensors['battery'] || {}, %w[level state voltage charging])
    out['battery'] = battery unless battery.empty?

    env = pick_present(sensors, %w[
      temperature extremeTemperature humidity atmosphericPressure
      lightLevel movement externalPower cellSignal
    ])
    out['environment'] = env unless env.empty?

    if sensors['orientation']
      out['orientation'] = pick_present(sensors['orientation'], %w[x y z])
    end
    if sensors['vibration']
      out['vibration'] = pick_present(sensors['vibration'], %w[x y z])
    end

    if sensors['securitySwitch']
      out['securitySwitch'] = pick_present(sensors['securitySwitch'],
                                            %w[state activations lastOpened lastClosed])
    end
    if sensors['sterilisation']
      out['sterilisation'] = pick_present(sensors['sterilisation'],
                                          %w[cycleCount cycleCountReadAt uptimeInSeconds uptimeReadAt])
    end

    out.empty? ? nil : out
  end

  def parse_secondary(obs)
    {
      'id' => obs['id'],
      'displayId' => obs['displayId'],
      'model' => parse_model(obs['model']),
      'reportType' => obs['reportType']
    }.merge(pick_present(obs, %w[name reportEventId reportId rssi isPaired]))
  end

  def parse_third_party(obs)
    base = { 'type' => obs['type'], 'id' => obs['id'], 'displayId' => obs['displayId'] }
    case obs['type']
    when 'reelable'
      base.merge(pick_present(obs, %w[temperature]))
    when 'chorus'
      base.merge(pick_present(obs, %w[temperature sequenceNumber batteryLevel rssi tamper]))
    when 'cableSeal'
      base.merge(pick_present(obs, %w[
        state open highTemperature lowBattery
        temperature batteryLevel counter rssi
      ]))
    else
      # Forward-compat: round-trip unknown beacon types under 'raw'.
      base.merge('raw' => obs)
    end
  end

  def parse_network_observations(networks)
    return nil if networks.nil?

    out = {}
    %w[wifi cells lteNeighbourCells].each do |k|
      out[k] = networks[k] if array_present?(networks[k])
    end
    out.empty? ? nil : out
  end

  def parse_time_series(ts)
    return nil if ts.nil?

    out = ts.each_with_object({}) do |(k, series), acc|
      acc[k] = series if array_present?(series)
    end
    out.empty? ? nil : out
  end

  def present?(value)
    !value.nil?
  end

  def array_present?(value)
    value.is_a?(Array) && !value.empty?
  end

  def pick_present(src, keys)
    return {} if src.nil?

    keys.each_with_object({}) do |k, acc|
      acc[k] = src[k] if present?(src[k])
    end
  end
end
