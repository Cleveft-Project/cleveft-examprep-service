package com.cleveft.examprepservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record GenerateQuizRequest(

        /**
         * Quiz one lecture. Mutually exclusive with {@link #courseCode} — a
         * quiz is scoped either to a single recording or to a whole course,
         * and "both" has no meaning.
         */
        UUID lectureId,

        /**
         * Quiz a whole course, drawing questions across every completed
         * lecture in it. Normalised by the caller.
         */
        String courseCode,

        @Min(value = 3, message = "A quiz needs at least 3 questions")
        @Max(value = 20, message = "A quiz may have at most 20 questions")
        Integer questionCount,

        @Pattern(regexp = "EASY|MEDIUM|HARD", message = "Difficulty must be EASY, MEDIUM or HARD")
        String difficulty,

        /**
         * Bias questions toward topics the student is weakest on. This is the
         * point of tracking mastery at all — a quiz that re-tests what someone
         * already knows does not move them forward.
         */
        Boolean focusOnWeakAreas
) {

    public boolean isCourseScoped() {
        return courseCode != null && !courseCode.isBlank();
    }

    /**
     * Exactly one scope must be given.
     *
     * <p>Checked here rather than with bean validation because the rule is a
     * relationship between two fields, and a request naming both a lecture and
     * a course is ambiguous rather than merely invalid — silently preferring
     * one would quiz the student on something they did not ask for.
     */
    public boolean hasExactlyOneScope() {
        return (lectureId != null) ^ isCourseScoped();
    }

    public int effectiveQuestionCount() {
        return questionCount == null ? 8 : questionCount;
    }

    public String effectiveDifficulty() {
        return difficulty == null || difficulty.isBlank() ? "MEDIUM" : difficulty;
    }

    public boolean shouldFocusOnWeakAreas() {
        return Boolean.TRUE.equals(focusOnWeakAreas);
    }
}
