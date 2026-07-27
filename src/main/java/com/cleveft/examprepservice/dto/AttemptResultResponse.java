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
        List<String> weakTopics,
        OffsetDateTime completedAt
) {

    public static AttemptResultResponse from(QuizAttempt attempt, List<String> weakTopics) {
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
                attempt.getCompletedAt());
    }
}
