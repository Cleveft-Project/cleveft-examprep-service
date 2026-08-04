package com.cleveft.examprepservice.repository;

import com.cleveft.examprepservice.model.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {

    /** Erases this student's rows when their account is deleted. */
    long deleteByUserId(UUID userId);

    List<QuizAttempt> findByUserIdOrderByCompletedAtDesc(UUID userId);

    /**
     * Quizzes taken per student since a point in time, for a whole cohort.
     *
     * <p>Counts attempts, not scores. The course leaderboard ranks effort, and
     * the moment a score enters it becomes an ability ranking — which would
     * push away exactly the students who most need to keep opening the app.
     */
    @Query("""
            select a.userId, count(a)
              from QuizAttempt a
             where a.userId in :userIds
               and a.completedAt >= :since
             group by a.userId
            """)
    List<Object[]> countByUsersSince(@Param("userIds") List<UUID> userIds,
                                     @Param("since") OffsetDateTime since);

    /** Backs the performance-trend chart on the exam prep screen. */
    List<QuizAttempt> findTop20ByUserIdOrderByCompletedAtAsc(UUID userId);

    List<QuizAttempt> findByUserIdAndLectureIdOrderByCompletedAtDesc(UUID userId, UUID lectureId);

    long countByUserId(UUID userId);
}
