package com.cleveft.examprepservice.controller;

import com.cleveft.examprepservice.dto.AttemptResultResponse;
import com.cleveft.examprepservice.dto.GenerateQuizRequest;
import com.cleveft.examprepservice.dto.QuizResponse;
import com.cleveft.examprepservice.dto.LectureReadinessResponse;
import com.cleveft.examprepservice.dto.TopicAnswerResponse;
import com.cleveft.examprepservice.dto.ReadinessResponse;
import com.cleveft.examprepservice.dto.SubmitAttemptRequest;
import com.cleveft.examprepservice.exception.ApiException;
import com.cleveft.examprepservice.model.ExamSummary;
import com.cleveft.examprepservice.service.ExamPrepService;
import com.cleveft.examprepservice.service.ExamSummaryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Quiz generation, grading and exam-readiness intelligence.
 */
@RestController
@RequestMapping("/api/v1/examprep")
public class ExamPrepController {

    private final ExamPrepService examPrepService;
    private final ExamSummaryService examSummaryService;

    public ExamPrepController(ExamPrepService examPrepService, ExamSummaryService examSummaryService) {
        this.examPrepService = examPrepService;
        this.examSummaryService = examSummaryService;
    }

    // ---- quizzes -----------------------------------------------------

    @PostMapping("/quizzes")
    public ResponseEntity<QuizResponse> generateQuiz(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody GenerateQuizRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(examPrepService.generateQuiz(requireUserId(userId), request));
    }

    @GetMapping("/quizzes")
    public ResponseEntity<List<QuizResponse>> listQuizzes(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(value = "lectureId", required = false) UUID lectureId) {

        return ResponseEntity.ok(examPrepService.listQuizzes(requireUserId(userId), lectureId));
    }

    @GetMapping("/quizzes/{quizId}")
    public ResponseEntity<QuizResponse> getQuiz(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID quizId) {

        return ResponseEntity.ok(examPrepService.getQuiz(requireUserId(userId), quizId));
    }

    @DeleteMapping("/quizzes/{quizId}")
    public ResponseEntity<Void> deleteQuiz(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID quizId) {

        examPrepService.deleteQuiz(requireUserId(userId), quizId);
        return ResponseEntity.noContent().build();
    }

    // ---- attempts ----------------------------------------------------

    @PostMapping("/quizzes/{quizId}/attempts")
    public ResponseEntity<AttemptResultResponse> submitAttempt(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID quizId,
            @Valid @RequestBody SubmitAttemptRequest request) {

        return ResponseEntity.ok(
                examPrepService.submitAttempt(requireUserId(userId), quizId, request));
    }

    @GetMapping("/attempts")
    public ResponseEntity<List<AttemptResultResponse>> listAttempts(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        return ResponseEntity.ok(examPrepService.listAttempts(requireUserId(userId)));
    }

    // ---- readiness ---------------------------------------------------

    @GetMapping("/readiness")
    public ResponseEntity<ReadinessResponse> readiness(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        return ResponseEntity.ok(examPrepService.readiness(requireUserId(userId)));
    }

    /**
     * The questions this student was asked on one topic, and how they answered.
     *
     * <p>What a mastery percentage cannot tell you. Reached by tapping a topic
     * on the readiness card, so the number stops being a verdict and becomes a
     * way back into the material.
     *
     * @param courseCode optional, so a topic opened from one course's card does
     *                   not return answers from another that used the same tag
     */
    @GetMapping("/readiness/topics/{topic}/answers")
    public ResponseEntity<List<TopicAnswerResponse>> topicAnswers(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String topic,
            @RequestParam(value = "courseCode", required = false) String courseCode) {

        return ResponseEntity.ok(
                examPrepService.topicAnswers(requireUserId(userId), topic, courseCode));
    }

    // ---- summaries ---------------------------------------------------

    /**
     * Readiness for one lecture — what the lecture screen's Exam prep tab
     * shows. Scoped so that screen never depends on the rest of the library.
     */
    @GetMapping("/readiness/lectures/{lectureId}")
    public ResponseEntity<LectureReadinessResponse> lectureReadiness(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID lectureId) {

        return ResponseEntity.ok(
                examPrepService.lectureReadiness(requireUserId(userId), lectureId));
    }

    @GetMapping("/summaries/{lectureId}")
    public ResponseEntity<ExamSummary> summary(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID lectureId,
            @RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {

        return ResponseEntity.ok(
                examSummaryService.getOrCreate(requireUserId(userId), lectureId, refresh));
    }

    private static UUID requireUserId(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.unauthorized("Authentication required.");
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("Malformed identity header.");
        }
    }
}
