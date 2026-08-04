package com.cleveft.examprepservice.controller;

import com.cleveft.examprepservice.repository.ExamSummaryRepository;
import com.cleveft.examprepservice.repository.QuizAttemptRepository;
import com.cleveft.examprepservice.repository.QuizRepository;
import com.cleveft.examprepservice.repository.TopicAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service surface, outside {@code /api/v1/**} and so unroutable from
 * the gateway. See the transcription service's equivalent for the reasoning.
 *
 * <p>Counts only, never scores. The leaderboard this feeds ranks effort.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    private final QuizAttemptRepository attemptRepository;
    private final QuizRepository quizRepository;
    private final TopicAnalyticsRepository analyticsRepository;
    private final ExamSummaryRepository summaryRepository;

    public InternalController(QuizAttemptRepository attemptRepository,
                              QuizRepository quizRepository,
                              TopicAnalyticsRepository analyticsRepository,
                              ExamSummaryRepository summaryRepository) {
        this.attemptRepository = attemptRepository;
        this.quizRepository = quizRepository;
        this.analyticsRepository = analyticsRepository;
        this.summaryRepository = summaryRepository;
    }

    @GetMapping("/activity/quizzes")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<UUID, Long>> quizCounts(
            @RequestParam("userIds") List<UUID> userIds,
            @RequestParam("since") OffsetDateTime since) {

        if (userIds.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : attemptRepository.countByUsersSince(userIds, since)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return ResponseEntity.ok(counts);
    }

    /**
     * Erases everything this service holds for a student.
     *
     * <p>Attempts before quizzes, and analytics before either, so nothing is
     * left referring to a row that has gone. There are no foreign keys between
     * these tables — they are joined by user id and lecture id rather than by
     * constraint — so the ordering is the only thing keeping it consistent.
     */
    @DeleteMapping("/users/{userId}")
    @Transactional
    public ResponseEntity<Void> eraseUser(@PathVariable UUID userId) {
        long attempts = attemptRepository.deleteByUserId(userId);
        long analytics = analyticsRepository.deleteByUserId(userId);
        long summaries = summaryRepository.deleteByUserId(userId);
        long quizzes = quizRepository.deleteByUserId(userId);

        log.info("Erased {} attempt(s), {} topic row(s), {} summary(ies) and {} quiz(zes) for {}",
                attempts, analytics, summaries, quizzes, userId);

        return ResponseEntity.noContent().build();
    }
}
