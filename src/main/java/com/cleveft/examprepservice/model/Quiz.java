package com.cleveft.examprepservice.model;

import com.cleveft.examprepservice.dto.QuizQuestion;
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
 * A generated quiz, mapped onto {@code exam_prep.quizzes}.
 *
 * <p>Questions live in a JSONB column rather than a child table. They are always
 * read and written as a complete set, never queried individually, so a separate
 * table would buy joins and no query capability anyone needs.
 */
@Entity
@Table(name = "quizzes", schema = "exam_prep")
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Null when the quiz spans a whole course — see {@link #courseCode}. */
    @Column(name = "lecture_id", updatable = false)
    private UUID lectureId;

    /**
     * Set instead of {@code lectureId} when the quiz spans a whole course.
     * Exactly one of the two is ever populated — the database enforces it.
     */
    @Column(name = "course_code", length = 64)
    private String courseCode;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "difficulty", nullable = false, length = 20)
    private String difficulty = "MEDIUM";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "questions", nullable = false, columnDefinition = "jsonb")
    private List<QuizQuestion> questions;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Quiz() {
    }

    /** A quiz on one lecture. */
    public Quiz(UUID userId, UUID lectureId, String title, String difficulty, List<QuizQuestion> questions) {
        this(userId, lectureId, null, title, difficulty, questions);
    }

    public Quiz(UUID userId, UUID lectureId, String courseCode, String title,
                String difficulty, List<QuizQuestion> questions) {
        this.courseCode = courseCode;
        this.userId = userId;
        this.lectureId = lectureId;
        this.title = title;
        this.difficulty = difficulty;
        this.questions = questions;
    }

    public UUID getId() {
        return id;
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

    public String getTitle() {
        return title;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public List<QuizQuestion> getQuestions() {
        return questions;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
