package com.cleveft.examprepservice.dto;

import com.cleveft.examprepservice.model.QuizAttempt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttemptResultResponse(
        UUID attemptId,
        UUID quizId,
        /** Null when the quiz spanned a whole course. */
        UUID lectureId,
        /** Set instead of {@code lectureId} for a course-wide quiz. */
        String courseCode,
        int score,
        int totalQuestions,
        int percentage,
        List<GradedAnswer> answers,
        /** Topics this attempt got at least one question wrong on. */
        List<String> weakTopics,
        /**
         * Topics every question was answered correctly on.
         *
         * <p>Added because a result that only lists failures tells a student
         * half of what they sat the quiz to find out. Knowing a topic is solid
         * is what lets them stop revising it — which is the whole point of
         * measuring readiness rather than just scoring.
         */
        List<String> strongTopics,
        OffsetDateTime completedAt
) {

    public static AttemptResultResponse from(QuizAttempt attempt,
                                             List<String> weakTopics,
                                             List<String> strongTopics) {
        int percentage = attempt.getTotalQuestions() == 0
                ? 0
                : (int) Math.round(attempt.scoreRatio() * 100);

        return new AttemptResultResponse(
                attempt.getId(),
                attempt.getQuizId(),
                attempt.getLectureId(),
                attempt.getCourseCode(),
                attempt.getScore(),
                attempt.getTotalQuestions(),
                percentage,
                attempt.getAnswers(),
                weakTopics,
                strongTopics,
                attempt.getCompletedAt());
    }
}
