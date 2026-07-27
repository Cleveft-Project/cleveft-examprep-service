package com.cleveft.examprepservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * The graded outcome of one answer, persisted with the attempt so a student can
 * review exactly what they got wrong later.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GradedAnswer(
        String questionId,
        Integer selectedIndex,
        Integer correctIndex,
        boolean correct,
        String topicTag,
        String explanation,
        /**
         * The lecture the question came from, so mastery can be credited to the
         * right lecture even when the quiz spanned a whole course.
         */
        UUID lectureId
) {
}
