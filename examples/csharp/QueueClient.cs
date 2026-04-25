using System.Threading.Channels;

namespace LocoAware.Webhook;

/// <summary>
/// Stand-in queue client. Backed by an in-memory <see cref="Channel{T}"/>.
///
/// TODO: replace with your real broker:
///   - AWS SQS / SNS        AWSSDK.SQS, AWSSDK.SNS
///   - Azure Service Bus    Azure.Messaging.ServiceBus
///   - Apache Kafka         Confluent.Kafka
///   - RabbitMQ             RabbitMQ.Client
///   - Google Pub/Sub       Google.Cloud.PubSub.V1
///   - Or an `inbox` table drained by a <see cref="BackgroundService"/>.
///
/// <see cref="PublishAsync"/> must return only once the message is durably
/// persisted. Do not ack the HTTP request before this completes.
/// </summary>
public class QueueClient
{
    public record Envelope(string Topic, byte[] Body);

    private readonly Channel<Envelope> _channel = Channel.CreateUnbounded<Envelope>();

    public ChannelReader<Envelope> Reader => _channel.Reader;

    public virtual ValueTask PublishAsync(string topic, byte[] body)
        => _channel.Writer.WriteAsync(new Envelope(topic, body));
}
