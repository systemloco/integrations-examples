require_relative 'test_helper'
require_relative '../worker'

class WorkerTest < Minitest::Test
  PRIMARY = {
    'id' => 'rpt-primary-1',
    'time' => '2026-04-09T10:47:58.612Z',
    'device' => {
      'id' => 'dev-primary', 'displayId' => 'HGD-1',
      'model' => { 'family' => 'locoTrack', 'name' => 'HGD4', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1 },
      'labels' => []
    },
    'owner' => { 'id' => 'own-1', 'name' => 'Acme' },
    'sensors' => { 'battery' => { 'level' => 90 }, 'temperature' => 20 },
    'secondaryObservations' => [
      { 'id' => 'dev-tag-A', 'displayId' => 'TAG-A',
        'model' => { 'family' => 'locoCard', 'name' => 'E1BL', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0 },
        'reportType' => 'presence', 'reportId' => 'rpt-tag-A', 'rssi' => -50, 'isPaired' => true },
      { 'id' => 'dev-tag-B', 'displayId' => 'TAG-B',
        'model' => { 'family' => 'locoCard', 'name' => 'E1BL', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0 },
        'reportType' => 'event', 'reportId' => 'rpt-tag-B', 'rssi' => -70, 'isPaired' => true }
    ]
  }.freeze

  SECONDARY = {
    'id' => 'rpt-tag-A',
    'time' => '2026-04-09T10:48:51.000Z',
    'device' => {
      'id' => 'dev-tag-A', 'displayId' => 'TAG-A',
      'model' => { 'family' => 'locoCard', 'name' => 'E1BL', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0 },
      'labels' => []
    },
    'owner' => { 'id' => 'own-1', 'name' => 'Acme' },
    'reportedBy' => {
      'device' => { 'id' => 'dev-primary', 'displayId' => 'HGD-1',
                    'model' => { 'family' => 'locoTrack', 'name' => 'HGD4', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1 } },
      'report' => { 'id' => 'rpt-primary-1', 'time' => '2026-04-09T10:47:58.612Z' },
      'rssi' => -50
    },
    'sensors' => { 'battery' => { 'level' => 80 } }
  }.freeze

  def setup
    DocStore.reset_all
  end

  def test_primary_stores_report_and_touches_each_secondary
    Worker.handle_device_report(PRIMARY)

    stored = DeviceReports.get('rpt-primary-1')
    assert_equal 'primary', stored['role']
    assert_equal 2, stored['secondaries'].size

    primary_dev = Devices.get('dev-primary')
    assert_equal 'rpt-primary-1', primary_dev['lastReport']['id']
    assert_equal 90, primary_dev['lastSensors']['battery']['level']

    tag_a = Devices.get('dev-tag-A')
    tag_b = Devices.get('dev-tag-B')
    assert_equal 'dev-primary', tag_a['lastSeenBy']['primaryDeviceId']
    assert_equal(-50, tag_a['lastSeenBy']['rssi'])
    assert_equal(-70, tag_b['lastSeenBy']['rssi'])
    refute tag_a.key?('lastSensors'), 'primary presence touch must not write sensor data'
  end

  def test_secondary_writes_authoritative_sensors_and_primary_does_not_clobber
    Worker.handle_device_report(SECONDARY)
    Worker.handle_device_report(PRIMARY)

    tag_a = Devices.get('dev-tag-A')
    assert_equal 80, tag_a['lastSensors']['battery']['level']
    assert_equal 'dev-primary', tag_a['lastSeenBy']['primaryDeviceId']
    assert_equal 'rpt-tag-A', tag_a['lastReport']['id']
    assert_equal 'secondary', tag_a['lastReport']['role']
  end

  def test_worker_falls_back_to_relay_primary_when_tag_has_no_shipment_binding
    Shipments.seed('ship-7', 'boundDeviceIds' => ['dev-primary'])

    Worker.handle_device_report(SECONDARY)

    ship = Shipments.get('ship-7')
    assert_equal 1, ship['events'].size
    assert_equal 'rpt-tag-A', ship['events'][0]['_id']
  end

  def test_worker_appends_to_shipment_matched_by_device_name
    Shipments.seed('ship-42', 'boundDeviceNames' => ['SHIP-42'])

    payload = PRIMARY.merge(
      'id' => 'rpt-with-name',
      'device' => PRIMARY['device'].merge('name' => 'SHIP-42')
    )
    Worker.handle_device_report(payload)

    ship = Shipments.get('ship-42')
    assert_equal 1, ship['events'].size
  end

  def test_worker_is_null_safe_for_a_minimal_report
    Worker.handle_device_report(
      'id' => 'rpt-empty',
      'time' => '2026-04-09T10:00:00.000Z',
      'device' => {
        'id' => 'dev-empty', 'displayId' => 'X',
        'model' => { 'family' => 'locoTrack', 'name' => 'HGD4', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1 },
        'labels' => []
      },
      'owner' => { 'id' => 'own-1', 'name' => 'Acme' }
    )

    stored = DeviceReports.get('rpt-empty')
    assert_equal 'standalone', stored['role']
    assert_equal 'dev-empty', Devices.get('dev-empty')['_id']
  end
end
