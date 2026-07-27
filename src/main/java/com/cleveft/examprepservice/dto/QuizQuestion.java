package com.cleveft.examprepservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * One multiple-choice question.
 *
 * <p>Note that {@code correctIndex} and {@code explanation} are stripped before
 * a quiz is sent to the client to be taken — see {@link #withoutAnswer()}.
 * Shipping the answer key to the device and trusting the client not to look at
 * it would make the whole readiness score meaningless.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuizQuestion(
        String id,
        String prompt,
        List<String> options,
        Integer correctIndex,
        String explanation,
        String topicTag,
        /**
         * The lecture this question was written from.
         *
         * <p>Carried per question, not per quiz, because a course quiz draws
         * on several lectures and mastery is recorded per (user, lecture,
         * topic). Without it, every answer in a course quiz would be credited
         * to one arbitrary lecture.
         */
        UUID lectureId
) {

    public QuizQuestion withoutAnswer() {
        return new QuizQuestion(id, prompt, options, null, null, topicTag, lectureId);
    }

    /** A copy pinned to the lecture it was generated from. */
    public QuizQuestion forLecture(UUID lecture) {
        return new QuizQuestion(id, prompt, options, correctIndex, explanation, topicTag, lecture);
    }

    public boolean isCorrect(Integer selectedIndex) {
        return correctIndex != null && correctIndex.equals(selectedIndex);
    }
}
