package com.cleveft.examprepservice.repository;

import com.cleveft.examprepservice.model.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    List<Quiz> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Quiz> findByUserIdAndLectureIdOrderByCreatedAtDesc(UUID userId, UUID lectureId);

    Optional<Quiz> findByIdAndUserId(UUID id, UUID userId);
}
