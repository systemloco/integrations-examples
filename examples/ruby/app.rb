require 'sinatra/base'
require 'json'
require 'openssl'
require 'base64'

require_relative 'queue_client'
require_relative 'repositories'

class LocoAwareWebhook < Sinatra::Base
  # Sinatra 4 / Rack 3 enables Rack::Protection::HostAuthorization by default,
  # which 403s any request whose Host header isn't on the allow-list. Webhook
  # receivers are intentionally reached via the public hostname your ingress
  # (ALB, nginx, ngrok…) presents, so disable host pinning here.
  #
  # In production, prefer locking this down to your real public hostname:
  #   set :host_authorization, permitted_hosts: ['hooks.example.com']
  set :host_authorization, permitted_hosts: []

  # --------------------------------------------------------------------------
  # HMAC signature check — runs before every /webhooks/* handler.
  # --------------------------------------------------------------------------
  before '/webhooks/*' do
    # Read the raw body once. Rack 3's input stream isn't rewindable under
    # Rack::Lint (Sinatra enables Lint in development), so don't try to seek.
    @raw_body = request.body.read

    secret = ENV['LOCOAWARE_WEBHOOK_SECRET']
    if secret
      header = (ENV['LOCOAWARE_SIGNATURE_HEADER'] || 'X-LocoAware-Signature')
        .upcase.tr('-', '_')
      received = request.env["HTTP_#{header}"]
      halt 401, 'missing signature' if received.nil? || received.empty?

      # LocoAware sends base64(HMAC-SHA256(body, secret)).
      expected = Base64.strict_encode64(OpenSSL::HMAC.digest('SHA256', secret, @raw_body))
      halt 401, 'bad signature' unless secure_compare(expected, received)
    end
  end

  # --------------------------------------------------------------------------
  # Shipments — low volume. Handle inline.
  # --------------------------------------------------------------------------
  post '/webhooks/shipments' do
    event = JSON.parse(@raw_body)

    shipment_id = event.dig('shipment', 'id')
    Shipments.append_event(shipment_id, event) if shipment_id

    if event['type'] == 'report'
      device_id = event.dig('payload', 'device', 'id')
      Devices.update_latest(device_id, event) if device_id && Devices.exists?(device_id)
    end

    status 200
  end

  # --------------------------------------------------------------------------
  # Assets — low volume. Handle inline.
  # --------------------------------------------------------------------------
  post '/webhooks/assets' do
    event = JSON.parse(@raw_body)

    asset_name = event.dig('asset', 'name')
    asset_id   = event.dig('asset', 'id')

    if (shipment_id = Shipments.find_id_by_asset_name_or_id(asset_name, asset_id))
      Shipments.append_event(shipment_id, event)
    end

    Assets.update_latest(asset_id, event) if asset_id && Assets.exists?(asset_id)

    status 200
  end

  # --------------------------------------------------------------------------
  # Device Events — medium volume, bursty. Enqueue.
  # --------------------------------------------------------------------------
  post '/webhooks/device-events' do
    # Do not process inline — a zone change across a large fleet can produce
    # dozens of events per second. Push to the broker and ack.
    QueueClient.publish('locoaware.device-events', @raw_body)
    status 202
  end

  # --------------------------------------------------------------------------
  # Device Reports V2 — highest volume. Enqueue.
  # --------------------------------------------------------------------------
  post '/webhooks/device-reports' do
    # Firehose — a single company can push hundreds of reports per second.
    QueueClient.publish('locoaware.device-reports', @raw_body)
    status 202
  end

  private

  def secure_compare(a, b)
    return false unless a.bytesize == b.bytesize

    l = a.unpack("C#{a.bytesize}")
    res = 0
    b.each_byte { |byte| res |= byte ^ l.shift }
    res.zero?
  end
end
