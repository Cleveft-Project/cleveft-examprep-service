package com.cleveft.examprepservice.repository;

import com.cleveft.examprepservice.model.ExamSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExamSummaryRepository extends JpaRepository<ExamSummary, UUID> {

    /** Erases this student's rows when their account is deleted. */
    long deleteByUserId(UUID userId);

    Optional<ExamSummary> findByUserIdAndLectureId(UUID userId, UUID lectureId);
}
