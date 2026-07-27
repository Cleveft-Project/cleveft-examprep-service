package com.cleveft.examprepservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A cached exam-focused summary for one lecture, mapped onto
 * {@code exam_prep.summaries}.
 *
 * <p>Cached rather than regenerated per request: producing one costs a full
 * model call over the whole transcript, and the transcript does not change
 * between reads.
 */
@Entity
@Table(name = "summaries", schema = "exam_prep")
public class ExamSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "lecture_id", nullable = false, updatable = false)
    private UUID lectureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "summary_text", columnDefinition = "TEXT")
    private String summaryText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_concepts", columnDefinition = "jsonb")
    private List<Map<String, Object>> keyConcepts;

    /** [{topic, reason, likelihood}] — what is most likely to be examined. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "likely_exam_topics", columnDefinition = "jsonb")
    private List<Map<String, Object>> likelyExamTopics;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public ExamSummary() {
    }

    public ExamSummary(UUID userId, UUID lectureId, String summaryText,
                       List<Map<String, Object>> keyConcepts,
                       List<Map<String, Object>> likelyExamTopics) {
        this.userId = userId;
        this.lectureId = lectureId;
        this.summaryText = summaryText;
        this.keyConcepts = keyConcepts;
        this.likelyExamTopics = likelyExamTopics;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLectureId() {
        return lectureId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public List<Map<String, Object>> getKeyConcepts() {
        return keyConcepts;
    }

    public List<Map<String, Object>> getLikelyExamTopics() {
        return likelyExamTopics;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
