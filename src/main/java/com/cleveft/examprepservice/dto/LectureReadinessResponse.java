package com.cleveft.examprepservice.dto;

import java.util.List;

/**
 * Everything exam-prep knows about one lecture.
 *
 * <p>A lecture is the unit a quiz is taken against, so it is the unit that owns
 * a readiness score, weak areas, mastered topics and blind spots. This exists
 * so the lecture screen can ask about *its* lecture directly, rather than
 * pulling the whole account's readiness and filtering client-side — which would
 * make a screen about one recording depend on every other one.
 *
 * @param assessed    false when this lecture has never been quizzed; the
 *                    percent is then 0 for want of data, not for failure
 * @param blindSpots  topics this lecture teaches that have never been tested
 *                    or asked about — invisible to the score, which is exactly
 *                    what makes them worth naming
 */
public record LectureReadinessResponse(
        String lectureId,
        String title,
        String courseCode,
        int readinessPercent,
        String verdict,
        boolean assessed,
        int topicsAssessed,
        int quizzesTaken,
        List<ReadinessResponse.TopicMastery> weakAreas,
        List<ReadinessResponse.TopicMastery> strongAreas,
        List<String> blindSpots,
        List<ReadinessResponse.TrendPoint> trend
) {
}
