package com.cleveft.examprepservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pulls "topics this student keeps asking about" from the query service.
 *
 * <p>Repeated questions on a subject are the earliest available signal that a
 * student has not settled it — usually well before they ever sit a quiz on it.
 * Failures here are non-fatal; readiness then rests on quiz results alone.
 */
@Component
public class QueryInsightsClient {

    private static final Logger log = LoggerFactory.getLogger(QueryInsightsClient.class);

    private final RestClient restClient;

    public QueryInsightsClient(
            @Value("${cleveft.query-service.url:http://localhost:8081}") String baseUrl,
            @Value("${cleveft.query-service.timeout-ms:10000}") int timeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public List<TopicInsight> topicInsights(UUID userId) {
        try {
            List<TopicInsight> insights = restClient.get()
                    .uri("/api/v1/query/insights/topics")
                    .header("X-User-Id", userId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<TopicInsight>>() {
                    });

            return insights == null ? List.of() : insights;

        } catch (RestClientException e) {
            log.warn("Query insights unavailable for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopicInsight(String topic, long queryCount, OffsetDateTime lastAsked) {
    }
}
