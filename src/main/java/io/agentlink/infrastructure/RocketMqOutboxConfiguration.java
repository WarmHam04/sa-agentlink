package io.agentlink.infrastructure;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = {"agentlink.outbox.rocketmq.endpoints", "agentlink.outbox.rocketmq.topic"})
public class RocketMqOutboxConfiguration {
    @Bean
    public ClientServiceProvider rocketMqClientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean(destroyMethod = "close")
    public Producer agentLinkRocketMqProducer(
            ClientServiceProvider provider,
            @Value("${agentlink.outbox.rocketmq.endpoints}") String endpoints,
            @Value("${agentlink.outbox.rocketmq.topic}") String topic,
            @Value("${agentlink.outbox.rocketmq.access-key:}") String accessKey,
            @Value("${agentlink.outbox.rocketmq.secret-key:}") String secretKey,
            @Value("${agentlink.outbox.rocketmq.request-timeout:PT10S}") Duration requestTimeout
    ) throws Exception {
        var builder = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .setRequestTimeout(requestTimeout);
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(accessKey, secretKey));
        }
        return provider.newProducerBuilder()
                .setClientConfiguration(builder.build())
                .setTopics(topic)
                .build();
    }

    @Bean
    public RocketMqMessageFactory rocketMqMessageFactory(
            ClientServiceProvider provider,
            OutboxEnvelopeFactory envelopes,
            @Value("${agentlink.outbox.rocketmq.topic}") String topic
    ) {
        return new RocketMqMessageFactory(provider, envelopes, topic);
    }
}
