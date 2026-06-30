require_relative 'test_helper'
require_relative '../device_report_parser'

class DeviceReportParserTest < Minitest::Test
  PRIMARY = {
    'id' => 'rpt-primary-1',
    'time' => '2026-04-09T10:47:58.612Z',
    'rxTime' => '2026-04-09T10:47:58.716Z',
    'device' => {
      'id' => 'dev-primary', 'displayId' => 'HGD-1',
      'model' => { 'name' => 'HGD4', 'family' => 'locoTrack', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1 },
      'labels' => ['cold-chain']
    },
    'owner' => { 'id' => 'own-1', 'name' => 'Acme' },
    'reportReasons' => ['report'],
    'location' => { 'type' => 'wifi', 'global' => { 'lat' => 1.0, 'lon' => 2.0 } },
    'sensors' => {
      'battery' => { 'level' => 100, 'state' => 'good' },
      'temperature' => 25.1,
      'orientation' => { 'x' => 0.0, 'y' => 0.0, 'z' => -1.0 }
    },
    'secondaryObservations' => [
      {
        'id' => 'dev-tag-A', 'displayId' => 'TAG-A',
        'model' => { 'name' => 'E1BL', 'family' => 'locoCard', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0 },
        'reportType' => 'presence', 'reportId' => 'rpt-tag-A', 'rssi' => -53, 'isPaired' => true
      }
    ],
    'thirdPartyObservations' => [
      {
        'type' => 'cableSeal', 'id' => 'A2:A2:A2:00:07:AB', 'displayId' => 'A2A2A20007AB',
        'state' => 'closed', 'open' => false, 'temperature' => 21, 'counter' => 7
      }
    ]
  }.freeze

  SECONDARY = {
    'id' => 'rpt-tag-A',
    'time' => '2026-04-09T10:48:51.000Z',
    'device' => {
      'id' => 'dev-tag-A', 'displayId' => 'TAG-A',
      'model' => { 'name' => 'E1BL', 'family' => 'locoCard', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0 },
      'labels' => []
    },
    'owner' => { 'id' => 'own-1', 'name' => 'Acme' },
    'reportedBy' => {
      'device' => {
        'id' => 'dev-primary', 'displayId' => 'HGD-1',
        'model' => { 'name' => 'HGD4', 'family' => 'locoTrack', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1 }
      },
      'report' => { 'id' => 'rpt-primary-1', 'time' => '2026-04-09T10:47:58.612Z' },
      'rssi' => -53
    },
    'sensors' => { 'battery' => { 'level' => 80 } },
    'timeSeries' => { 'temperature' => [{ 'time' => '2026-04-09T10:36:00.000Z', 'value' => 22.6 }] }
  }.freeze

  SPARSE = {
    'id' => 'rpt-sparse',
    'time' => '2026-04-09T10:00:00.000Z',
    'device' => {
      'id' => 'dev-sparse', 'displayId' => 'DS-1',
      'model' => { 'name' => 'HGD4', 'family' => 'locoTrack', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1 },
      'labels' => []
    },
    'owner' => { 'id' => 'own-1', 'name' => 'Owner' }
  }.freeze

  def test_primary_report_is_tagged_role_primary
    doc = DeviceReportParser.parse(PRIMARY)
    assert_equal 'primary', doc['role']
    assert_equal 1, doc['secondaries'].size
    assert_equal 'rpt-tag-A', doc['secondaries'][0]['reportId']
    assert_equal true, doc['secondaries'][0]['isPaired']
  end

  def test_secondary_report_is_tagged_role_secondary
    doc = DeviceReportParser.parse(SECONDARY)
    assert_equal 'secondary', doc['role']
    assert_equal 'dev-primary', doc['relayedBy']['device']['id']
    assert_equal 'rpt-primary-1', doc['relayedBy']['reportId']
    assert_equal(-53, doc['relayedBy']['rssi'])
  end

  def test_primary_to_secondary_link_can_be_traced_both_ways
    primary = DeviceReportParser.parse(PRIMARY)
    secondary = DeviceReportParser.parse(SECONDARY)

    matching = primary['secondaries'].find { |s| s['reportId'] == secondary['_id'] }
    refute_nil matching
    assert_equal secondary['device']['id'], matching['id']

    assert_equal primary['_id'], secondary['relayedBy']['reportId']
    assert_equal primary['device']['id'], secondary['relayedBy']['device']['id']
  end

  def test_standalone_report_has_no_secondaries_or_relayed_by
    doc = DeviceReportParser.parse(SPARSE)
    assert_equal 'standalone', doc['role']
    refute doc.key?('secondaries')
    refute doc.key?('relayedBy')
  end

  def test_sparse_report_omits_absent_fields
    doc = DeviceReportParser.parse(SPARSE)
    %w[rxTime location sensors relayedBy thirdPartyObservations timeSeries].each do |k|
      refute doc.key?(k), "expected '#{k}' to be absent on a sparse report"
    end
    assert_equal 'rpt-sparse', doc['_id']
    assert_equal [], doc['device']['labels']
  end

  def test_sensors_are_grouped_into_battery_environment_and_vectors
    doc = DeviceReportParser.parse(PRIMARY)
    assert_equal 100, doc['sensors']['battery']['level']
    assert_equal 25.1, doc['sensors']['environment']['temperature']
    assert_equal({ 'x' => 0.0, 'y' => 0.0, 'z' => -1.0 }, doc['sensors']['orientation'])
  end

  def test_third_party_observation_keeps_type_specific_fields
    doc = DeviceReportParser.parse(PRIMARY)
    seal = doc['thirdPartyObservations'][0]
    assert_equal 'cableSeal', seal['type']
    assert_equal 'closed', seal['state']
    assert_equal 21, seal['temperature']
    assert_equal 7, seal['counter']
  end

  def test_unknown_third_party_type_falls_back_to_raw
    payload = SPARSE.merge('thirdPartyObservations' => [
                             { 'type' => 'futureBeacon', 'id' => 'F1', 'displayId' => 'F1', 'mystery' => true }
                           ])
    beacon = DeviceReportParser.parse(payload)['thirdPartyObservations'][0]
    assert_equal 'futureBeacon', beacon['type']
    assert_equal true, beacon['raw']['mystery']
  end

  def test_time_series_passes_through_and_drops_empty_series
    doc = DeviceReportParser.parse(SECONDARY)
    assert_equal 1, doc['timeSeries']['temperature'].size

    empty = DeviceReportParser.parse(SPARSE.merge('timeSeries' => { 'humidity' => [] }))
    refute empty.key?('timeSeries')
  end

  def test_project_latest_state_returns_per_device_snapshot
    doc = DeviceReportParser.parse(PRIMARY)
    state = DeviceReportParser.project_latest_state(doc)
    assert_equal doc['device']['id'], state['_id']
    assert_equal doc['_id'], state['lastReport']['id']
    assert_equal 'primary', state['lastReport']['role']
    assert state['lastSensors']
    assert state['lastLocation']
  end

  def test_project_latest_state_omits_absent_optionals
    doc = DeviceReportParser.parse(SPARSE)
    state = DeviceReportParser.project_latest_state(doc)
    assert_equal 'dev-sparse', state['_id']
    refute state.key?('lastLocation')
    refute state.key?('lastSensors')
    refute state.key?('relayedBy')
  end
end
