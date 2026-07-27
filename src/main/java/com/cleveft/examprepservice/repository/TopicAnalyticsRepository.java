package com.cleveft.examprepservice.repository;

import com.cleveft.examprepservice.model.TopicAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TopicAnalyticsRepository extends JpaRepository<TopicAnalytics, UUID> {

    List<TopicAnalytics> findByUserId(UUID userId);

    /**
     * Mastery for one topic on one lecture.
     *
     * <p>This is the identity of a mastery row. Looking a topic up by name
     * alone was the old behaviour and is what let a topic taught in three
     * lectures share a single score.
     */
    Optional<TopicAnalytics> findByUserIdAndLectureIdAndTopicTag(
            UUID userId, UUID lectureId, String topicTag);

    /**
     * Every row carrying this topic, across all lectures.
     *
     * <p>Used for query-derived signal: "I keep looking up normalisation" is
     * not a fact about one lecture, so it applies to every lecture that
     * teaches it.
     */
    List<TopicAnalytics> findByUserIdAndTopicTag(UUID userId, String topicTag);

    /** The lecture-less row for a topic nothing has been quizzed on yet. */
    Optional<TopicAnalytics> findByUserIdAndLectureIdIsNullAndTopicTag(UUID userId, String topicTag);

    /** Weakest first — this is the "where do I need to revise" list. */
    List<TopicAnalytics> findByUserIdAndAttemptCountGreaterThanOrderByMasteryScoreAsc(
            UUID userId, int minimumAttempts);
}
