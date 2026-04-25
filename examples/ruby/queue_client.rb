require 'json'

# Stand-in queue client.
#
# TODO: swap for your real broker:
#   - AWS SQS / SNS      aws-sdk-sqs, aws-sdk-sns
#   - RabbitMQ           bunny
#   - Redis / Sidekiq    sidekiq
#   - Shoryuken (SQS)    shoryuken
#   - Or an `inbox` table drained by a Sidekiq/Shoryuken worker.
#
# #publish must return only once the message is durably persisted. Don't ack
# the HTTP request before #publish returns — if the process dies between the
# two, you lose the message (you already said 2xx to LocoAware).
class QueueClient
  QUEUES = Hash.new { |h, k| h[k] = Queue.new }

  def self.publish(topic, raw_body)
    QUEUES[topic] << raw_body
  end

  def self.subscribe(topic, &handler)
    Thread.new do
      loop do
        body = QUEUES[topic].pop
        begin
          handler.call(JSON.parse(body))
        rescue => e
          warn "[worker] #{topic} handler failed: #{e.message}"
          # With a real broker, don't ack — message returns to the queue / DLQ.
        end
      end
    end
  end
end
