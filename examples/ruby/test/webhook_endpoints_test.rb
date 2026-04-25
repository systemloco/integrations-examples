require_relative 'test_helper'

class WebhookEndpointsTest < Minitest::Test
  include Rack::Test::Methods

  def app
    LocoAwareWebhook
  end

  def setup
    ENV.delete('LOCOAWARE_WEBHOOK_SECRET')
    QueueClient::QUEUES.clear
  end

  def test_shipments_endpoint_returns_200_and_appends
    appended = []
    Shipments.stub(:append_event, ->(id, evt) { appended << [id, evt] }) do
      post '/webhooks/shipments', JSON.generate(
        id: 'evt_1', type: 'positionReport', shipment: { id: 'ship_1' }
      ), { 'CONTENT_TYPE' => 'application/json' }
    end

    assert_equal 200, last_response.status
    assert_equal 1, appended.size
    assert_equal 'ship_1', appended[0][0]
  end

  def test_shipment_report_syncs_the_embedded_device
    updated = []
    Devices.stub(:exists?, true) do
      Devices.stub(:update_latest, ->(id, _evt) { updated << id }) do
        post '/webhooks/shipments', JSON.generate(
          id: 'evt_2', type: 'report',
          shipment: { id: 'ship_2' },
          payload: { device: { id: 'dev_2' } }
        ), { 'CONTENT_TYPE' => 'application/json' }
      end
    end

    assert_equal 200, last_response.status
    assert_includes updated, 'dev_2'
  end

  def test_assets_endpoint_returns_200_and_looks_up_shipment
    lookups = []
    Shipments.stub(:find_id_by_asset_name_or_id, ->(name, id) { lookups << [name, id]; nil }) do
      post '/webhooks/assets', JSON.generate(
        id: 'evt_3', asset: { id: 'asset_3', name: 'pallet-3' }
      ), { 'CONTENT_TYPE' => 'application/json' }
    end

    assert_equal 200, last_response.status
    assert_equal [['pallet-3', 'asset_3']], lookups
  end

  def test_device_events_endpoint_returns_202_and_enqueues
    body = JSON.generate(id: 'evt_4', device: { id: 'dev_4' })
    post '/webhooks/device-events', body, { 'CONTENT_TYPE' => 'application/json' }

    assert_equal 202, last_response.status
    assert_equal 1, QueueClient::QUEUES['locoaware.device-events'].size
    assert_equal body, QueueClient::QUEUES['locoaware.device-events'].pop
  end

  def test_device_reports_endpoint_returns_202_and_enqueues
    body = JSON.generate(device: { id: 'dev_5', name: 'ship_5' })
    post '/webhooks/device-reports', body, { 'CONTENT_TYPE' => 'application/json' }

    assert_equal 202, last_response.status
    assert_equal 1, QueueClient::QUEUES['locoaware.device-reports'].size
    assert_equal body, QueueClient::QUEUES['locoaware.device-reports'].pop
  end
end
