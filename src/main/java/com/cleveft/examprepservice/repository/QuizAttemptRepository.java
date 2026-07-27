package com.cleveft.examprepservice.repository;

import com.cleveft.examprepservice.model.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {

    List<QuizAttempt> findByUserIdOrderByCompletedAtDesc(UUID userId);

    /** Backs the performance-trend chart on the exam prep screen. */
    List<QuizAttempt> findTop20ByUserIdOrderByCompletedAtAsc(UUID userId);

    List<QuizAttempt> findByUserIdAndLectureIdOrderByCompletedAtDesc(UUID userId, UUID lectureId);

    long countByUserId(UUID userId);
}
