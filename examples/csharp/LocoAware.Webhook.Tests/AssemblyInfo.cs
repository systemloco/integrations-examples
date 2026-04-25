using Xunit;

// Test classes mutate the LOCOAWARE_WEBHOOK_SECRET env var that the app reads
// at startup. Run sequentially so the two factories don't race each other.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
