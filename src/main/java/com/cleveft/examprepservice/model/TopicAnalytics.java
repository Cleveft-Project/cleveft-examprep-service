package com.cleveft.examprepservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Rolling per-topic mastery, mapped onto {@code exam_prep.topic_analytics}.
 *
 * <p>One row per (user, topic). Updated on every quiz attempt and refreshed from
 * the query service's question log, so both "I keep getting this wrong" and
 * "I keep having to ask about this" feed the same score.
 */
@Entity
@Table(name = "topic_analytics", schema = "exam_prep")
public class TopicAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "lecture_id")
    private UUID lectureId;

    @Column(name = "topic_tag", nullable = false)
    private String topicTag;

    /** How many times the student has asked the RAG chat about this topic. */
    @Column(name = "query_count", nullable = false)
    private int queryCount;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "mastery_score", nullable = false)
    private double masteryScore;

    @Column(name = "last_queried")
    private OffsetDateTime lastQueried;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public TopicAnalytics() {
    }

    public TopicAnalytics(UUID userId, UUID lectureId, String topicTag) {
        this.userId = userId;
        this.lectureId = lectureId;
        this.topicTag = topicTag;
    }

    /**
     * Folds one more graded answer into this topic's record.
     */
    public void recordAnswer(boolean correct) {
        attemptCount++;
        if (correct) {
            correctCount++;
        }
        recomputeMastery();
    }

    public void recordQueries(int count, OffsetDateTime lastAsked) {
        this.queryCount = count;
        this.lastQueried = lastAsked;
        recomputeMastery();
    }

    /**
     * Mastery is quiz accuracy, damped by how often the student still has to
     * look this topic up.
     *
     * <p>The damping matters: someone can score well on a topic they only get
     * right by re-reading it every single time, and calling that "mastered"
     * would hide exactly the gap this feature exists to surface. Repeated
     * questions cost up to 25% of the score, saturating around eight lookups so
     * that heavy use of the chat never drives a topic to zero on its own.
     *
     * <p>With no attempts recorded there is no evidence either way, so the score
     * stays at zero and the topic is reported as "not yet assessed" rather than
     * as a weakness.
     */
    private void recomputeMastery() {
        if (attemptCount == 0) {
            this.masteryScore = 0.0;
            return;
        }

        double accuracy = (double) correctCount / attemptCount;
        double lookupPenalty = 0.25 * Math.min(1.0, queryCount / 8.0);

        this.masteryScore = Math.max(0.0, Math.min(1.0, accuracy * (1.0 - lookupPenalty)));
    }

    public boolean isAssessed() {
        return attemptCount > 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getLectureId() {
        return lectureId;
    }

    public void setLectureId(UUID lectureId) {
        this.lectureId = lectureId;
    }

    public String getTopicTag() {
        return topicTag;
    }

    public int getQueryCount() {
        return queryCount;
    }

    public int getCorrectCount() {
        return correctCount;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public double getMasteryScore() {
        return masteryScore;
    }

    public OffsetDateTime getLastQueried() {
        return lastQueried;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
