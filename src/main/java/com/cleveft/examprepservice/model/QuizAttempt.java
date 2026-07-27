package com.cleveft.examprepservice.model;

import com.cleveft.examprepservice.dto.GradedAnswer;
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
import java.util.UUID;

/**
 * A completed quiz attempt, mapped onto {@code exam_prep.quiz_attempts}.
 * Attempts are the raw material for every performance trend in the app.
 */
@Entity
@Table(name = "quiz_attempts", schema = "exam_prep")
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "quiz_id", nullable = false, updatable = false)
    private UUID quizId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Null when the quiz spanned a whole course — see {@link #courseCode}. */
    @Column(name = "lecture_id", updatable = false)
    private UUID lectureId;

    /** Set instead of {@link #lectureId} when the quiz spanned a whole course. */
    @Column(name = "course_code", length = 64, updatable = false)
    private String courseCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", nullable = false, columnDefinition = "jsonb")
    private List<GradedAnswer> answers;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "total_questions", nullable = false)
    private int totalQuestions;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public QuizAttempt() {
    }

    public QuizAttempt(UUID quizId, UUID userId, UUID lectureId,
                       List<GradedAnswer> answers, int score, int totalQuestions) {
        this(quizId, userId, lectureId, null, answers, score, totalQuestions);
    }

    public QuizAttempt(UUID quizId, UUID userId, UUID lectureId, String courseCode,
                       List<GradedAnswer> answers, int score, int totalQuestions) {
        this.quizId = quizId;
        this.userId = userId;
        this.lectureId = lectureId;
        this.courseCode = courseCode;
        this.answers = answers;
        this.score = score;
        this.totalQuestions = totalQuestions;
        this.completedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getQuizId() {
        return quizId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public UUID getLectureId() {
        return lectureId;
    }

    public List<GradedAnswer> getAnswers() {
        return answers;
    }

    public int getScore() {
        return score;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public double scoreRatio() {
        return totalQuestions == 0 ? 0 : (double) score / totalQuestions;
    }
}
