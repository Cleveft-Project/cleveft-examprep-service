package com.cleveft.examprepservice.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One question a student was asked on a topic, and how they answered it.
 *
 * <p>Assembled from two places: the attempt records what was chosen and whether
 * it was right, while the quiz holds the prompt and the options. Neither alone
 * can show a student the question they got wrong, which is the one thing a
 * mastery percentage cannot tell them.
 *
 * @param selectedIndex null when the question was left unanswered
 * @param quizTitle     which sitting it came from, so repeated attempts at the
 *                      same topic are distinguishable
 */
public record TopicAnswerResponse(
        String questionId,
        String prompt,
        List<String> options,
        Integer selectedIndex,
        Integer correctIndex,
        boolean correct,
        String explanation,
        UUID lectureId,
        String quizTitle,
        OffsetDateTime answeredAt
) {
}
