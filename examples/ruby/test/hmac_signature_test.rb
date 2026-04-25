require_relative 'test_helper'
require 'openssl'
require 'base64'

class HmacSignatureTest < Minitest::Test
  include Rack::Test::Methods

  SECRET = 'shhh'.freeze

  def app
    LocoAwareWebhook
  end

  def setup
    ENV['LOCOAWARE_WEBHOOK_SECRET'] = SECRET
    QueueClient::QUEUES.clear
  end

  def teardown
    ENV.delete('LOCOAWARE_WEBHOOK_SECRET')
  end

  def sign(body, secret)
    Base64.strict_encode64(OpenSSL::HMAC.digest('SHA256', secret, body))
  end

  def test_rejects_request_without_signature_header
    post '/webhooks/shipments', '{"shipment":{"id":"x"}}', { 'CONTENT_TYPE' => 'application/json' }
    assert_equal 401, last_response.status
  end

  def test_rejects_request_with_bad_signature
    body = '{"shipment":{"id":"x"}}'
    post '/webhooks/shipments', body, {
      'CONTENT_TYPE' => 'application/json',
      'HTTP_X_LOCOAWARE_SIGNATURE' => sign(body, 'wrong-secret'),
    }
    assert_equal 401, last_response.status
  end

  def test_accepts_correctly_signed_request
    body = '{"id":"evt_signed","shipment":{"id":"ship_signed"}}'
    Shipments.stub(:append_event, ->(_id, _evt) {}) do
      post '/webhooks/shipments', body, {
        'CONTENT_TYPE' => 'application/json',
        'HTTP_X_LOCOAWARE_SIGNATURE' => sign(body, SECRET),
      }
    end
    assert_equal 200, last_response.status
  end

  def test_accepts_correctly_signed_device_report
    body = '{"device":{"id":"dev_signed"}}'
    post '/webhooks/device-reports', body, {
      'CONTENT_TYPE' => 'application/json',
      'HTTP_X_LOCOAWARE_SIGNATURE' => sign(body, SECRET),
    }
    assert_equal 202, last_response.status
  end
end
