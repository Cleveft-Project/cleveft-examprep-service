package com.cleveft.examprepservice.client;

import com.cleveft.examprepservice.exception.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads lecture content from the transcription service.
 *
 * <p>Quizzes have to be generated from what the lecturer actually said, so this
 * service needs the transcript — but it does not own it, and reaching into the
 * {@code transcription} schema directly would couple two services to one table
 * definition. Everything goes over HTTP with the student's own identity
 * forwarded, which also means ownership checks are enforced in exactly one place.
 */
@Component
public class TranscriptionClient {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionClient.class);

    private final RestClient restClient;

    public TranscriptionClient(
            @Value("${cleveft.transcription-service.url:http://localhost:8082}") String baseUrl,
            @Value("${cleveft.transcription-service.timeout-ms:30000}") int timeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public LectureDetail getLecture(UUID userId, UUID lectureId) {
        try {
            LectureDetail lecture = restClient.get()
                    .uri("/api/v1/transcriptions/{id}", lectureId)
                    .header("X-User-Id", userId.toString())
                    .retrieve()
                    .body(LectureDetail.class);

            if (lecture == null) {
                throw ApiException.notFound("Lecture not found.");
            }
            return lecture;

        } catch (HttpClientErrorException.NotFound e) {
            // A lecture the student does not own, or that never existed. This is
            // the caller's mistake, not an upstream outage, and collapsing it
            // into a 502 would tell them to retry something that can never work.
            throw ApiException.notFound("Lecture not found.");

        } catch (RestClientException e) {
            log.error("Could not load lecture {} for user {}: {}", lectureId, userId, e.getMessage());
            throw ApiException.badGateway("Could not load that lecture right now.");
        }
    }

    public List<LectureSummary> listLectures(UUID userId) {
        try {
            List<LectureSummary> lectures = restClient.get()
                    .uri("/api/v1/transcriptions")
                    .header("X-User-Id", userId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<LectureSummary>>() {
                    });

            return lectures == null ? List.of() : lectures;

        } catch (RestClientException e) {
            log.error("Could not list lectures for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LectureDetail(
            UUID id,
            String title,
            String courseCode,
            String status,
            String fullTranscript,
            List<Map<String, Object>> structuredNotes,
            List<Map<String, Object>> keyConcepts
    ) {
        public boolean isReady() {
            return "COMPLETED".equals(status) && fullTranscript != null && !fullTranscript.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LectureSummary(
            UUID id,
            String title,
            String courseCode,
            String status,
            /** Key-concept terms — display only. */
            List<String> topics,
            /** Canonical tags, shared vocabulary with topic_analytics. */
            List<String> topicTags
    ) {
    }
}
