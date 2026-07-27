package com.cleveft.examprepservice.dto;

import com.cleveft.examprepservice.model.Quiz;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A quiz as the client sees it.
 *
 * <p>Built via {@link #forTaking} the answer key is removed; via
 * {@link #withAnswers} it is included, which is only ever done after the
 * student has submitted.
 */
public record QuizResponse(
        UUID id,
        /** Null for a course-wide quiz — see {@link #courseCode}. */
        UUID lectureId,
        /** Set instead of {@code lectureId} when the quiz spans a course. */
        String courseCode,
        String title,
        String difficulty,
        int questionCount,
        List<QuizQuestion> questions,
        OffsetDateTime createdAt
) {

    public static QuizResponse forTaking(Quiz quiz) {
        return new QuizResponse(
                quiz.getId(),
                quiz.getLectureId(),
                quiz.getCourseCode(),
                quiz.getTitle(),
                quiz.getDifficulty(),
                quiz.getQuestions().size(),
                quiz.getQuestions().stream().map(QuizQuestion::withoutAnswer).toList(),
                quiz.getCreatedAt());
    }

    public static QuizResponse withAnswers(Quiz quiz) {
        return new QuizResponse(
                quiz.getId(),
                quiz.getLectureId(),
                quiz.getCourseCode(),
                quiz.getTitle(),
                quiz.getDifficulty(),
                quiz.getQuestions().size(),
                quiz.getQuestions(),
                quiz.getCreatedAt());
    }
}
