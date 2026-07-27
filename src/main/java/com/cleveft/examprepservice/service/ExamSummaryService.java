package com.cleveft.examprepservice.service;

import com.cleveft.examprepservice.ai.GeminiJsonClient;
import com.cleveft.examprepservice.client.TranscriptionClient;
import com.cleveft.examprepservice.exception.ApiException;
import com.cleveft.examprepservice.model.ExamSummary;
import com.cleveft.examprepservice.repository.ExamSummaryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Produces the exam-focused summary for a lecture: what it covered, what
 * matters most, and what is most likely to be assessed.
 */
@Service
public class ExamSummaryService {

    private static final int MAX_TRANSCRIPT_CHARS = 100_000;

    private static final String INSTRUCTION = """
            You are a university tutor preparing a student for an exam on one lecture.

            Return ONLY a JSON object of this exact shape:

            {
              "summary": "...",
              "keyConcepts": [{"term": "...", "detail": "..."}],
              "likelyExamTopics": [
                {"topic": "...", "reason": "...", "likelihood": "HIGH|MEDIUM|LOW"}
              ]
            }

            Rules:
            - "summary" is 3-5 paragraphs covering what the lecture actually taught, in its order.
            - "keyConcepts" lists the formulas, definitions and results worth memorising.
            - "likelyExamTopics" ranks what is most likely to be examined. Base "reason" on
              evidence from the transcript — time spent, worked examples, repetition, or the
              lecturer explicitly flagging something as important. Do not guess a paper.
            - Ground everything in the transcript. Add nothing the lecturer did not say.
            """;

    private final ExamSummaryRepository summaryRepository;
    private final TranscriptionClient transcriptionClient;
    private final GeminiJsonClient client;

    public ExamSummaryService(ExamSummaryRepository summaryRepository,
                              TranscriptionClient transcriptionClient,
                              GeminiJsonClient client) {
        this.summaryRepository = summaryRepository;
        this.transcriptionClient = transcriptionClient;
        this.client = client;
    }

    /**
     * @param refresh regenerate even if a summary is already cached
     */
    @Transactional
    public ExamSummary getOrCreate(UUID userId, UUID lectureId, boolean refresh) {
        if (!refresh) {
            var cached = summaryRepository.findByUserIdAndLectureId(userId, lectureId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        TranscriptionClient.LectureDetail lecture = transcriptionClient.getLecture(userId, lectureId);
        if (!lecture.isReady()) {
            throw ApiException.badRequest(
                    "That lecture is still being processed. Try again once it has finished.");
        }

        String transcript = lecture.fullTranscript();
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            transcript = transcript.substring(0, MAX_TRANSCRIPT_CHARS);
        }

        JsonNode result = client.generateJson(INSTRUCTION,
                "Lecture title: " + lecture.title() + "\n\nTranscript:\n" + transcript);

        // Replace rather than accumulate: one summary per lecture, enforced by a
        // unique constraint on (user_id, lecture_id).
        summaryRepository.findByUserIdAndLectureId(userId, lectureId)
                .ifPresent(summaryRepository::delete);
        summaryRepository.flush();

        return summaryRepository.save(new ExamSummary(
                userId,
                lectureId,
                result.path("summary").asText(""),
                readObjectArray(result.path("keyConcepts")),
                readObjectArray(result.path("likelyExamTopics"))));
    }

    private List<Map<String, Object>> readObjectArray(JsonNode array) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }

        for (JsonNode element : array) {
            Map<String, Object> entry = new LinkedHashMap<>();
            element.fields().forEachRemaining(field -> entry.put(field.getKey(), field.getValue().asText()));
            if (!entry.isEmpty()) {
                result.add(entry);
            }
        }
        return result;
    }
}
