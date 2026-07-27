package com.cleveft.examprepservice.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The exam-readiness picture rendered on the dashboard.
 *
 * @param readinessPercent  overall readiness, 0-100
 * @param weakAreas         topics with the lowest mastery, weakest first
 * @param strongAreas       topics the student has demonstrably mastered
 * @param blindSpots        topics from their lectures they have never been
 *                          tested on and never asked about — invisible to any
 *                          score, which is exactly what makes them dangerous
 * @param trend             score of each recent attempt, oldest first
 */
public record ReadinessResponse(
        int readinessPercent,
        String verdict,
        int topicsAssessed,
        int quizzesTaken,
        List<TopicMastery> weakAreas,
        List<TopicMastery> strongAreas,
        List<String> blindSpots,
        List<TrendPoint> trend,
        /**
         * The same picture, split per course, strongest first.
         *
         * <p>A student takes eight courses a semester and sits eight separate
         * exams. One blended number across all of them can read 67% while
         * hiding 95% in one course and 30% in another — precisely the course
         * they should be revising. The overall figure above is kept as a
         * summary, but this is the actionable breakdown.
         */
        List<CourseReadiness> courses
) {

    public record TopicMastery(
            String topic,
            int masteryPercent,
            int attempts,
            int queryCount,
            OffsetDateTime lastQueried
    ) {
    }

    public record TrendPoint(OffsetDateTime at, int percentage) {
    }

    /**
     * @param courseCode   normalised grouping key; null for ungrouped lectures
     * @param courseLabel  the student's own spelling, for display
     * @param assessed     false when no lecture in this course has been quizzed
     *                     yet, in which case {@code readinessPercent} is
     *                     meaningless and must not be shown as a score
     * @param lectures     the lectures making up this course, weakest first
     */
    public record CourseReadiness(
            String courseCode,
            String courseLabel,
            int readinessPercent,
            String verdict,
            boolean assessed,
            int lectureCount,
            int topicsAssessed,
            int quizzesTaken,
            List<TopicMastery> weakAreas,
            List<TopicMastery> strongAreas,
            List<LectureReadiness> lectures
    ) {
    }

    /**
     * Readiness for a single lecture — the atom the whole model is built on.
     *
     * <p>A quiz is taken against one lecture, so this is the only level at
     * which the score is measured rather than derived. Course readiness is the
     * roll-up of these; there is deliberately no figure above that, because
     * there is no exam that spans every course a student takes.
     *
     * @param assessed false when this lecture has never been quizzed. Its
     *                 percent is then 0 only because nothing is known, which is
     *                 not the same as scoring zero.
     */
    public record LectureReadiness(
            String lectureId,
            String title,
            int readinessPercent,
            boolean assessed,
            int topicsAssessed,
            int quizzesTaken,
            List<TopicMastery> weakAreas
    ) {
    }
}
