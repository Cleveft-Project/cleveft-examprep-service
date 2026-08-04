package com.cleveft.examprepservice.service;

import com.cleveft.examprepservice.client.QueryInsightsClient;
import com.cleveft.examprepservice.client.TranscriptionClient;
import com.cleveft.examprepservice.dto.AttemptResultResponse;
import com.cleveft.examprepservice.dto.GenerateQuizRequest;
import com.cleveft.examprepservice.dto.GradedAnswer;
import com.cleveft.examprepservice.dto.LectureReadinessResponse;
import com.cleveft.examprepservice.dto.TopicAnswerResponse;
import com.cleveft.examprepservice.dto.QuizQuestion;
import com.cleveft.examprepservice.dto.QuizResponse;
import com.cleveft.examprepservice.dto.ReadinessResponse;
import com.cleveft.examprepservice.dto.SubmitAttemptRequest;
import com.cleveft.examprepservice.exception.ApiException;
import com.cleveft.examprepservice.model.Quiz;
import com.cleveft.examprepservice.model.QuizAttempt;
import com.cleveft.examprepservice.model.TopicAnalytics;
import com.cleveft.examprepservice.repository.QuizAttemptRepository;
import com.cleveft.examprepservice.repository.QuizRepository;
import com.cleveft.examprepservice.repository.TopicAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ExamPrepService {

    private static final Logger log = LoggerFactory.getLogger(ExamPrepService.class);

    /** Below this a topic is reported as a weak area. */
    private static final double WEAK_THRESHOLD = 0.6;
    /** At or above this a topic counts as mastered. */
    private static final double STRONG_THRESHOLD = 0.8;

    /**
     * How many questions a topic needs before one clean sweep counts as
     * understanding it. See where it is used in {@link #submitAttempt}.
     */
    private static final int UNDERSTOOD_MIN_QUESTIONS = 2;

    private final QuizRepository quizRepository;
    private final QuizAttemptRepository attemptRepository;
    private final TopicAnalyticsRepository analyticsRepository;
    private final QuizGenerationService quizGenerationService;
    private final TranscriptionClient transcriptionClient;
    private final QueryInsightsClient queryInsightsClient;

    public ExamPrepService(QuizRepository quizRepository,
                           QuizAttemptRepository attemptRepository,
                           TopicAnalyticsRepository analyticsRepository,
                           QuizGenerationService quizGenerationService,
                           TranscriptionClient transcriptionClient,
                           QueryInsightsClient queryInsightsClient) {
        this.quizRepository = quizRepository;
        this.attemptRepository = attemptRepository;
        this.analyticsRepository = analyticsRepository;
        this.quizGenerationService = quizGenerationService;
        this.transcriptionClient = transcriptionClient;
        this.queryInsightsClient = queryInsightsClient;
    }

    // ------------------------------------------------------------------
    //  Quizzes
    // ------------------------------------------------------------------

    /** At most this many lectures feed one course quiz — see {@link #generateForCourse}. */
    private static final int MAX_COURSE_LECTURES = 5;

    @Transactional
    public QuizResponse generateQuiz(UUID userId, GenerateQuizRequest request) {
        if (!request.hasExactlyOneScope()) {
            throw ApiException.badRequest("Choose either a lecture or a course to be quizzed on.");
        }

        List<String> focusTopics = request.shouldFocusOnWeakAreas()
                ? weakTopicNames(userId)
                : List.of();

        Quiz quiz = request.isCourseScoped()
                ? generateForCourse(userId, request, focusTopics)
                : generateForLecture(userId, request, focusTopics);

        log.info("Generated quiz {} with {} questions for user {}",
                quiz.getId(), quiz.getQuestions().size(), userId);

        // Answer key withheld until the attempt is submitted.
        return QuizResponse.forTaking(quiz);
    }

    private Quiz generateForLecture(UUID userId, GenerateQuizRequest request, List<String> focusTopics) {
        TranscriptionClient.LectureDetail lecture =
                transcriptionClient.getLecture(userId, request.lectureId());

        if (!lecture.isReady()) {
            throw ApiException.badRequest(
                    "That lecture is still being processed. Try again once it has finished.");
        }

        QuizGenerationService.GeneratedQuiz generated = quizGenerationService.generate(
                lecture,
                request.effectiveQuestionCount(),
                request.effectiveDifficulty(),
                focusTopics);

        return quizRepository.save(new Quiz(
                userId,
                request.lectureId(),
                generated.title(),
                request.effectiveDifficulty(),
                // Stamped here: the generator sees one lecture's JSON and has
                // no identity for it, but grading needs to know where every
                // question came from.
                pin(generated.questions(), lecture.id())));
    }

    /**
     * A quiz spanning a whole course.
     *
     * <p>Questions are generated per lecture and merged, rather than from one
     * combined transcript. Six lectures of text would blow past the model's
     * context, and — more importantly — a single call cannot reliably say
     * which lecture each question came from. Mastery is recorded per (user,
     * lecture, topic), so a mis-attributed question corrupts the very
     * per-lecture scores this design rests on. Generating per lecture makes
     * the attribution structural rather than something the model has to get
     * right.
     *
     * <p>The cost is one model call per lecture, which is why the lecture
     * count is capped: a student with twenty recordings in a course does not
     * want to wait for twenty calls, and a quiz drawn from the five they are
     * weakest on is a better quiz anyway.
     */
    private Quiz generateForCourse(UUID userId, GenerateQuizRequest request, List<String> focusTopics) {
        String courseKey = CourseCodes.normalise(request.courseCode());

        List<TranscriptionClient.LectureSummary> inCourse =
                transcriptionClient.listLectures(userId).stream()
                        .filter(lecture -> courseKey.equals(CourseCodes.normalise(lecture.courseCode())))
                        .filter(lecture -> "COMPLETED".equals(lecture.status()))
                        // A course quiz is the closest thing Cleveft offers to a
                        // mock exam, so it is drawn from what the lecturer
                        // actually taught. Videos can still be practised
                        // against one at a time.
                        .filter(TranscriptionClient.LectureSummary::isExaminable)
                        .toList();

        if (inCourse.isEmpty()) {
            throw ApiException.badRequest(
                    "That course has no processed lectures yet. Record one, or wait for it to finish.");
        }

        // Weakest lectures first, so a capped selection spends its questions
        // where the student is actually struggling.
        Map<UUID, Double> masteryByLecture = averageMasteryByLecture(userId);
        List<TranscriptionClient.LectureSummary> chosen = inCourse.stream()
                .sorted(Comparator.comparingDouble(
                        lecture -> masteryByLecture.getOrDefault(lecture.id(), 0.0)))
                .limit(MAX_COURSE_LECTURES)
                .toList();

        int total = request.effectiveQuestionCount();
        List<QuizQuestion> merged = new ArrayList<>(total);
        String courseLabel = CourseCodes.display(
                inCourse.get(0).courseCode() == null ? request.courseCode() : inCourse.get(0).courseCode());

        for (int index = 0; index < chosen.size(); index++) {
            TranscriptionClient.LectureSummary summary = chosen.get(index);

            // Spread the remainder over the first few lectures rather than
            // dropping it, so 8 questions across 3 lectures is 3/3/2 and not
            // 2/2/2 with two questions quietly lost.
            int remainingLectures = chosen.size() - index;
            int share = (int) Math.ceil((double) (total - merged.size()) / remainingLectures);
            if (share <= 0) {
                break;
            }

            TranscriptionClient.LectureDetail detail =
                    transcriptionClient.getLecture(userId, summary.id());
            if (detail == null || !detail.isReady()) {
                continue;
            }

            try {
                QuizGenerationService.GeneratedQuiz part = quizGenerationService.generate(
                        detail, share, request.effectiveDifficulty(), focusTopics);
                merged.addAll(pin(part.questions(), summary.id()));
            } catch (RuntimeException e) {
                // One lecture failing should not lose the whole quiz — the
                // student still gets a usable paper from the rest.
                log.warn("Course quiz: lecture {} contributed nothing ({})", summary.id(), e.getMessage());
            }
        }

        if (merged.isEmpty()) {
            throw ApiException.badRequest(
                    "Could not write questions for that course right now. Please try again.");
        }

        return quizRepository.save(new Quiz(
                userId,
                null,
                courseKey,
                courseLabel + " — course quiz",
                request.effectiveDifficulty(),
                merged));
    }

    /** Mean mastery per lecture, for choosing which lectures a course quiz covers. */
    private Map<UUID, Double> averageMasteryByLecture(UUID userId) {
        Map<UUID, List<Double>> scores = new HashMap<>();
        for (TopicAnalytics topic : analyticsRepository.findByUserId(userId)) {
            if (topic.getLectureId() != null && topic.isAssessed()) {
                scores.computeIfAbsent(topic.getLectureId(), unused -> new ArrayList<>())
                        .add(topic.getMasteryScore());
            }
        }

        Map<UUID, Double> averages = new HashMap<>();
        scores.forEach((lectureId, values) -> averages.put(
                lectureId,
                values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));
        return averages;
    }

    private static List<QuizQuestion> pin(List<QuizQuestion> questions, UUID lectureId) {
        return questions.stream().map(question -> question.forLecture(lectureId)).toList();
    }

    @Transactional(readOnly = true)
    public List<QuizResponse> listQuizzes(UUID userId, UUID lectureId) {
        List<Quiz> quizzes = lectureId == null
                ? quizRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : quizRepository.findByUserIdAndLectureIdOrderByCreatedAtDesc(userId, lectureId);

        return quizzes.stream().map(QuizResponse::forTaking).toList();
    }

    @Transactional(readOnly = true)
    public QuizResponse getQuiz(UUID userId, UUID quizId) {
        return QuizResponse.forTaking(requireQuiz(userId, quizId));
    }

    @Transactional
    public void deleteQuiz(UUID userId, UUID quizId) {
        quizRepository.delete(requireQuiz(userId, quizId));
    }

    // ------------------------------------------------------------------
    //  Attempts
    // ------------------------------------------------------------------

    /**
     * Grades a submission server-side against the stored answer key and folds
     * the result into per-topic mastery.
     */
    @Transactional
    public AttemptResultResponse submitAttempt(UUID userId, UUID quizId, SubmitAttemptRequest request) {
        Quiz quiz = requireQuiz(userId, quizId);

        Map<String, QuizQuestion> byId = new HashMap<>();
        quiz.getQuestions().forEach(question -> byId.put(question.id(), question));

        Map<String, Integer> submitted = new HashMap<>();
        for (SubmitAttemptRequest.SubmittedAnswer answer : request.answers()) {
            if (answer.questionId() != null) {
                submitted.put(answer.questionId(), answer.selectedIndex());
            }
        }

        List<GradedAnswer> graded = new ArrayList<>(quiz.getQuestions().size());
        int score = 0;

        // Iterate the quiz, not the submission: an unanswered question must be
        // graded as wrong, and a submission naming questions that do not belong
        // to this quiz must not affect the score.
        for (QuizQuestion question : quiz.getQuestions()) {
            Integer selected = submitted.get(question.id());
            boolean correct = question.isCorrect(selected);
            if (correct) {
                score++;
            }

            graded.add(new GradedAnswer(
                    question.id(),
                    selected,
                    question.correctIndex(),
                    correct,
                    question.topicTag(),
                    question.explanation(),
                    // The question's own lecture, falling back to the quiz's
                    // for quizzes written before questions carried one.
                    question.lectureId() != null ? question.lectureId() : quiz.getLectureId()));
        }

        QuizAttempt attempt = attemptRepository.save(new QuizAttempt(
                quiz.getId(),
                userId,
                quiz.getLectureId(),
                quiz.getCourseCode(),
                graded,
                score,
                quiz.getQuestions().size()));

        /*
         * Practice on supporting material is not recorded as mastery.
         *
         * Mastery is what readiness is computed from, and readiness answers "am
         * I ready for the exam?" — a question only the lecturer's material can
         * answer. So a quiz taken against an imported video shows its score and
         * its topic breakdown exactly like any other, and changes nothing
         * behind the scenes.
         *
         * Deliberately symmetric. An earlier design let a video flag a topic as
         * weak while never crediting it as understood, on the grounds that
         * getting something wrong is evidence wherever it happens. True, but it
         * meant practice could quietly lower a readiness score it could never
         * raise — a meter that only moves one way is worse than one that plainly
         * says what it counts.
         */
        if (isExaminable(userId, quiz.getLectureId())) {
            updateTopicMastery(userId, graded);
        }

        // Group by topic first, then judge the topic rather than the answer.
        //
        // Scoring per answer would let a topic appear in both lists at once —
        // three questions on it, two right and one wrong — which tells the
        // student nothing. A topic is weak if any question on it was missed and
        // strong only if every one was answered correctly.
        Map<String, List<GradedAnswer>> byTopic = graded.stream()
                .filter(answer -> answer.topicTag() != null && !answer.topicTag().isBlank())
                .collect(Collectors.groupingBy(
                        GradedAnswer::topicTag, LinkedHashMap::new, Collectors.toList()));

        List<String> weakTopics = byTopic.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(answer -> !answer.correct()))
                .map(Map.Entry::getKey)
                .toList();

        // Understanding needs repetition behind it.
        //
        // One correct answer on a topic is as likely to be a lucky guess as
        // knowledge, and telling a student they understand something on that
        // evidence is worse than telling them nothing. Two or more questions
        // all answered correctly is a claim worth making — and it is the claim
        // that makes this different from the right/wrong list every quiz app
        // already shows.
        //
        // Deliberately asymmetric with weakness: a single miss is still worth
        // flagging, because a gap is evidence even once.
        List<String> strongTopics = byTopic.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= UNDERSTOOD_MIN_QUESTIONS)
                .filter(entry -> entry.getValue().stream().allMatch(GradedAnswer::correct))
                .map(Map.Entry::getKey)
                .toList();

        log.info("User {} scored {}/{} on quiz {}", userId, score, quiz.getQuestions().size(), quizId);

        return AttemptResultResponse.from(attempt, weakTopics, strongTopics);
    }

    @Transactional(readOnly = true)
    public List<AttemptResultResponse> listAttempts(UUID userId) {
        return attemptRepository.findByUserIdOrderByCompletedAtDesc(userId).stream()
                .map(attempt -> AttemptResultResponse.from(attempt, List.of(), List.of()))
                .toList();
    }

    /**
     * Records quiz outcomes against the lecture they were earned on.
     *
     * <p>Keyed by lecture as well as topic. Looking the topic up by name alone
     * meant a topic taught in three lectures shared one score, so quizzing
     * lecture 2 credited lecture 1 and left lecture 2 looking untouched — and
     * two courses using the same topic name moved each other's readiness.
     */
    /**
     * Whether a quiz's source counts towards the exam.
     *
     * <p>A course quiz has no lecture of its own, and is already generated from
     * examinable material only — so a null id is examinable by construction.
     *
     * <p>Fails open: if the transcription service cannot be reached, the attempt
     * is recorded as it always was. Silently discarding a student's quiz result
     * because another service was briefly down is the worse failure.
     */
    private boolean isExaminable(UUID userId, UUID lectureId) {
        if (lectureId == null) {
            return true;
        }
        try {
            return transcriptionClient.getLecture(userId, lectureId).isExaminable();
        } catch (RuntimeException e) {
            log.warn("Could not read the source of lecture {}; recording mastery as usual", lectureId);
            return true;
        }
    }

    private void updateTopicMastery(UUID userId, List<GradedAnswer> graded) {
        for (GradedAnswer answer : graded) {
            String topic = answer.topicTag();
            UUID lectureId = answer.lectureId();

            // Each answer carries its own lecture, which is what lets one
            // course quiz correctly improve six different lectures' scores
            // instead of piling all of them onto whichever lecture the quiz
            // happened to be filed under.
            if (topic == null || topic.isBlank() || lectureId == null) {
                continue;
            }

            TopicAnalytics analytics = analyticsRepository
                    .findByUserIdAndLectureIdAndTopicTag(userId, lectureId, topic)
                    .orElseGet(() -> {
                        // Promote the lecture-less row if one exists: it holds
                        // the query counts for this topic, and discarding them
                        // would silently reset "I keep looking this up".
                        return analyticsRepository
                                .findByUserIdAndLectureIdIsNullAndTopicTag(userId, topic)
                                .map(existing -> {
                                    existing.setLectureId(lectureId);
                                    return existing;
                                })
                                .orElseGet(() -> new TopicAnalytics(userId, lectureId, topic));
                    });

            analytics.recordAnswer(answer.correct());
            analyticsRepository.save(analytics);
        }
    }

    // ------------------------------------------------------------------
    //  Readiness
    // ------------------------------------------------------------------

    /**
     * Combines quiz performance with how often the student is still looking a
     * topic up, then reports what is weak, what is solid, and what has never
     * been checked at all.
     */
    @Transactional
    public ReadinessResponse readiness(UUID userId) {
        syncQueryInsights(userId);

        List<TopicAnalytics> topics = analyticsRepository.findByUserId(userId);
        List<TopicAnalytics> assessed = topics.stream()
                .filter(TopicAnalytics::isAssessed)
                .toList();

        List<ReadinessResponse.TopicMastery> weak = topSummarised(
                assessed.stream()
                        .filter(topic -> topic.getMasteryScore() < WEAK_THRESHOLD)
                        .sorted(Comparator.comparingDouble(TopicAnalytics::getMasteryScore))
                        .toList(),
                8);

        List<ReadinessResponse.TopicMastery> strong = topSummarised(
                assessed.stream()
                        .filter(topic -> topic.getMasteryScore() >= STRONG_THRESHOLD)
                        .sorted(Comparator.comparingDouble(TopicAnalytics::getMasteryScore).reversed())
                        .toList(),
                8);

        List<QuizAttempt> recent = attemptRepository.findTop20ByUserIdOrderByCompletedAtAsc(userId);
        List<ReadinessResponse.TrendPoint> trend = recent.stream()
                .map(attempt -> new ReadinessResponse.TrendPoint(
                        attempt.getCompletedAt(),
                        (int) Math.round(attempt.scoreRatio() * 100)))
                .toList();

        int readiness = computeReadiness(assessed, recent);

        return new ReadinessResponse(
                readiness,
                verdictFor(readiness, assessed.isEmpty()),
                assessed.size(),
                recent.size(),
                weak,
                strong,
                findBlindSpots(userId, topics),
                trend,
                readinessByCourse(userId, assessed));
    }

    /**
     * Readiness computed separately for each course.
     *
     * <p>Lectures are the only thing that knows its course, so the course of a
     * topic or an attempt is resolved through its lecture. One lecture listing
     * serves the whole calculation — resolving each lecture individually would
     * be a request per topic.
     *
     * <p>Ungrouped lectures get their own bucket rather than being dropped:
     * silently excluding them would make the per-course numbers disagree with
     * the overall one for no visible reason.
     */
    private List<ReadinessResponse.CourseReadiness> readinessByCourse(
            UUID userId, List<TopicAnalytics> assessed) {

        // Supporting material is dropped before anything is counted.
        //
        // Readiness answers one question — "am I ready for the exam?" — and the
        // exam comes from the lecturer. A video the student found to understand
        // a topic belongs in search, in the chat and in practice; letting it
        // into this calculation would let the meter climb on material no
        // examiner has seen.
        List<TranscriptionClient.LectureSummary> lectures =
                transcriptionClient.listLectures(userId).stream()
                        .filter(TranscriptionClient.LectureSummary::isExaminable)
                        .toList();

        if (lectures.isEmpty()) {
            return List.of();
        }

        // Index the raw material by lecture once, then roll upward. Building
        // per-lecture figures first is what makes course readiness an average
        // of real measurements rather than a separate calculation that can
        // disagree with the lectures it claims to summarise.
        Map<UUID, List<TopicAnalytics>> topicsByLecture = new HashMap<>();
        for (TopicAnalytics topic : assessed) {
            topicsByLecture.computeIfAbsent(topic.getLectureId(), unused -> new ArrayList<>())
                    .add(topic);
        }

        Map<UUID, List<QuizAttempt>> attemptsByLecture = new HashMap<>();
        // Course quizzes have no lecture of their own; they are folded into
        // the course pool further down. Their individual answers still reach
        // the right lectures, because mastery was recorded per answer.
        Map<String, List<QuizAttempt>> courseOnlyAttempts = new HashMap<>();

        for (QuizAttempt attempt : attemptRepository.findTop20ByUserIdOrderByCompletedAtAsc(userId)) {
            if (attempt.getLectureId() != null) {
                attemptsByLecture.computeIfAbsent(attempt.getLectureId(), unused -> new ArrayList<>())
                        .add(attempt);
            } else if (attempt.getCourseCode() != null) {
                courseOnlyAttempts.computeIfAbsent(attempt.getCourseCode(), unused -> new ArrayList<>())
                        .add(attempt);
            }
        }

        Map<String, String> labelByCourse = new LinkedHashMap<>();
        Map<String, List<ReadinessResponse.LectureReadiness>> lecturesByCourse = new LinkedHashMap<>();
        Map<String, List<TopicAnalytics>> topicsByCourse = new LinkedHashMap<>();
        Map<String, List<QuizAttempt>> attemptsByCourse = new LinkedHashMap<>();

        for (TranscriptionClient.LectureSummary lecture : lectures) {
            String key = CourseCodes.normalise(lecture.courseCode());
            // First spelling seen wins, so the label does not flicker between
            // "EE 355" and "ee355" depending on listing order.
            labelByCourse.putIfAbsent(key, CourseCodes.display(lecture.courseCode()));

            List<TopicAnalytics> lectureTopics =
                    topicsByLecture.getOrDefault(lecture.id(), List.of());
            List<QuizAttempt> lectureAttempts =
                    attemptsByLecture.getOrDefault(lecture.id(), List.of());

            boolean lectureAssessed = !lectureTopics.isEmpty() || !lectureAttempts.isEmpty();

            lecturesByCourse.computeIfAbsent(key, unused -> new ArrayList<>())
                    .add(new ReadinessResponse.LectureReadiness(
                            lecture.id().toString(),
                            lecture.title(),
                            lectureAssessed ? computeReadiness(lectureTopics, lectureAttempts) : 0,
                            lectureAssessed,
                            lectureTopics.size(),
                            lectureAttempts.size(),
                            lectureTopics.stream()
                                    .filter(topic -> topic.getMasteryScore() < WEAK_THRESHOLD)
                                    .sorted(Comparator.comparingDouble(TopicAnalytics::getMasteryScore))
                                    .limit(4)
                                    .map(ExamPrepService::toMastery)
                                    .toList()));

            topicsByCourse.computeIfAbsent(key, unused -> new ArrayList<>()).addAll(lectureTopics);
            attemptsByCourse.computeIfAbsent(key, unused -> new ArrayList<>()).addAll(lectureAttempts);
        }

        List<ReadinessResponse.CourseReadiness> result = new ArrayList<>();
        for (Map.Entry<String, List<ReadinessResponse.LectureReadiness>> entry
                : lecturesByCourse.entrySet()) {

            String key = entry.getKey();
            List<TopicAnalytics> courseTopics = topicsByCourse.getOrDefault(key, List.of());

            // Lecture-scoped attempts plus any taken against the course as a
            // whole. Omitting the latter would show a course as unassessed
            // immediately after the student sat a quiz on it.
            List<QuizAttempt> courseAttempts = new ArrayList<>(
                    attemptsByCourse.getOrDefault(key, List.of()));
            courseAttempts.addAll(courseOnlyAttempts.getOrDefault(key, List.of()));
            courseAttempts.sort(Comparator.comparing(QuizAttempt::getCompletedAt));

            boolean courseAssessed = !courseTopics.isEmpty() || !courseAttempts.isEmpty();

            int percent = courseAssessed ? computeReadiness(courseTopics, courseAttempts) : 0;

            List<ReadinessResponse.LectureReadiness> courseLectures = new ArrayList<>(entry.getValue());
            // Unassessed lectures last: they have no score to rank by, and
            // sorting them to the top as "0%" would bury the lecture the
            // student actually did badly on.
            courseLectures.sort(Comparator
                    .comparing(ReadinessResponse.LectureReadiness::assessed).reversed()
                    .thenComparingInt(ReadinessResponse.LectureReadiness::readinessPercent));

            result.add(new ReadinessResponse.CourseReadiness(
                    CourseCodes.isUngrouped(key) ? null : key,
                    labelByCourse.get(key),
                    percent,
                    verdictFor(percent, !courseAssessed),
                    courseAssessed,
                    courseLectures.size(),
                    courseTopics.size(),
                    courseAttempts.size(),
                    topSummarised(
                            courseTopics.stream()
                                    .filter(topic -> topic.getMasteryScore() < WEAK_THRESHOLD)
                                    .sorted(Comparator.comparingDouble(TopicAnalytics::getMasteryScore))
                                    .toList(),
                            5),
                    topSummarised(
                            courseTopics.stream()
                                    .filter(topic -> topic.getMasteryScore() >= STRONG_THRESHOLD)
                                    .sorted(Comparator.comparingDouble(TopicAnalytics::getMasteryScore).reversed())
                                    .toList(),
                            5),
                    courseLectures));
        }

        // Assessed courses first, weakest of those at the top: the point of the
        // breakdown is to surface the course in trouble, and an untouched
        // course reading 0% would otherwise outrank a course genuinely failing.
        result.sort(Comparator
                .comparing(ReadinessResponse.CourseReadiness::assessed).reversed()
                .thenComparingInt(ReadinessResponse.CourseReadiness::readinessPercent));
        return result;
    }

    /**
     * Overall readiness weights average topic mastery against recent quiz
     * performance.
     *
     * <p>Mastery alone is too slow to reflect a study session that just
     * happened; recent scores alone swing wildly on a single quiz. Weighting
     * mastery higher keeps the number stable while still moving when the
     * student makes progress today.
     */
    private int computeReadiness(List<TopicAnalytics> assessed, List<QuizAttempt> attempts) {
        if (assessed.isEmpty() && attempts.isEmpty()) {
            return 0;
        }

        double averageMastery = assessed.isEmpty() ? 0 : assessed.stream()
                .mapToDouble(TopicAnalytics::getMasteryScore)
                .average()
                .orElse(0);

        // Only the last five attempts: performance from a month ago says little
        // about readiness now.
        List<QuizAttempt> latest = attempts.size() <= 5
                ? attempts
                : attempts.subList(attempts.size() - 5, attempts.size());

        double recentAccuracy = latest.isEmpty() ? 0 : latest.stream()
                .mapToDouble(QuizAttempt::scoreRatio)
                .average()
                .orElse(0);

        if (assessed.isEmpty()) {
            return (int) Math.round(recentAccuracy * 100);
        }
        if (latest.isEmpty()) {
            return (int) Math.round(averageMastery * 100);
        }

        return (int) Math.round((averageMastery * 0.65 + recentAccuracy * 0.35) * 100);
    }

    /**
     * Collapses a sorted topic list to one entry per topic name.
     *
     * <p>Mastery is now per (lecture, topic), so a topic taught across three
     * lectures has three rows. That is correct for scoring but wrong for a
     * "your weak areas" list, which would otherwise print the same topic three
     * times. The input must already be sorted so that the row worth keeping
     * comes first — weakest first for weak lists, strongest first for strong.
     */
    private static List<ReadinessResponse.TopicMastery> topSummarised(
            List<TopicAnalytics> sorted, int limit) {

        Map<String, ReadinessResponse.TopicMastery> byTopic = new LinkedHashMap<>();
        for (TopicAnalytics topic : sorted) {
            byTopic.putIfAbsent(topic.getTopicTag(), toMastery(topic));
            if (byTopic.size() == limit) {
                break;
            }
        }
        return List.copyOf(byTopic.values());
    }

    private static String verdictFor(int readiness, boolean noData) {
        if (noData) {
            return "Take your first quiz to see where you stand.";
        }
        if (readiness >= 80) {
            return "You're exam ready. Keep the weak areas ticking over.";
        }
        if (readiness >= 60) {
            return "Solid foundation. Focus your revision on the weak areas below.";
        }
        if (readiness >= 40) {
            return "Getting there. Several topics still need real attention.";
        }
        return "Early days. Work through your weakest topics one at a time.";
    }

    /**
     * Topics from the student's lectures they have neither been quizzed on nor
     * asked about. A readiness score cannot see these, which is precisely why
     * they are worth naming.
     */
    /**
     * Readiness for a single lecture.
     *
     * <p>Scoped to one lecture on purpose: the lecture screen should not have
     * to load every other recording to describe the one the student opened,
     * and mastery is stored per (user, lecture, topic) so the answer is a
     * direct lookup rather than a filter over the whole account.
     */
    @Transactional
    public LectureReadinessResponse lectureReadiness(UUID userId, UUID lectureId) {
        syncQueryInsights(userId);

        TranscriptionClient.LectureDetail lecture = transcriptionClient.getLecture(userId, lectureId);
        if (lecture == null) {
            throw ApiException.notFound("Lecture not found.");
        }

        List<TopicAnalytics> topics = analyticsRepository.findByUserId(userId).stream()
                .filter(topic -> lectureId.equals(topic.getLectureId()))
                .filter(TopicAnalytics::isAssessed)
                .toList();

        List<QuizAttempt> attempts = attemptRepository.findTop20ByUserIdOrderByCompletedAtAsc(userId)
                .stream()
                .filter(attempt -> lectureId.equals(attempt.getLectureId()))
                .toList();

        boolean assessed = !topics.isEmpty() || !attempts.isEmpty();
        int percent = assessed ? computeReadiness(topics, attempts) : 0;

        return new LectureReadinessResponse(
                lectureId.toString(),
                lecture.title(),
                lecture.courseCode(),
                percent,
                verdictFor(percent, !assessed),
                assessed,
                topics.size(),
                attempts.size(),
                topSummarised(
                        topics.stream()
                                .filter(topic -> topic.getMasteryScore() < WEAK_THRESHOLD)
                                .sorted(Comparator.comparingDouble(TopicAnalytics::getMasteryScore))
                                .toList(),
                        8),
                topSummarised(
                        topics.stream()
                                .filter(topic -> topic.getMasteryScore() >= STRONG_THRESHOLD)
                                .sorted(Comparator.comparingDouble(TopicAnalytics::getMasteryScore).reversed())
                                .toList(),
                        8),
                blindSpotsForLecture(userId, lectureId, topics),
                attempts.stream()
                        .map(attempt -> new ReadinessResponse.TrendPoint(
                                attempt.getCompletedAt(),
                                (int) Math.round(attempt.scoreRatio() * 100)))
                        .toList());
    }

    /**
     * Topics this lecture teaches that it has never been tested on.
     *
     * <p>Restricted to the one lecture's own tags — the account-wide version
     * would report a gap from a different course entirely, which is no help
     * when you are revising this recording.
     */
    private List<String> blindSpotsForLecture(UUID userId, UUID lectureId, List<TopicAnalytics> tracked) {
        Set<String> touched = new LinkedHashSet<>();
        tracked.forEach(topic -> touched.add(normaliseTag(topic.getTopicTag())));

        Set<String> untouched = new LinkedHashSet<>();
        for (TranscriptionClient.LectureSummary lecture : transcriptionClient.listLectures(userId)) {
            if (!lectureId.equals(lecture.id()) || lecture.topicTags() == null) {
                continue;
            }
            for (String tag : lecture.topicTags()) {
                if (tag != null && !tag.isBlank() && !isCovered(normaliseTag(tag), touched)) {
                    untouched.add(tag);
                }
            }
        }

        return untouched.stream().limit(8).toList();
    }

    private List<String> findBlindSpots(UUID userId, List<TopicAnalytics> tracked) {
        // Compare tags to tags. Key-concept terms come from a different prompt
        // ("Beta (Current Gain)") than the tags mastery is stored under
        // ("current gain"), so comparing across the two never matches and every
        // topic looks untested forever.
        Set<String> touched = new LinkedHashSet<>();
        tracked.forEach(topic -> touched.add(normaliseTag(topic.getTopicTag())));

        Set<String> untouched = new LinkedHashSet<>();
        for (TranscriptionClient.LectureSummary lecture : transcriptionClient.listLectures(userId)) {
            // A blind spot is something you will be examined on and have not
            // revisited. A video you imported and never quizzed is not that —
            // flagging it would tell a student they have a gap in material their
            // lecturer never set.
            if (lecture.topicTags() == null || !lecture.isExaminable()) {
                continue;
            }
            for (String tag : lecture.topicTags()) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                if (!isCovered(normaliseTag(tag), touched)) {
                    untouched.add(tag);
                }
            }
        }

        return untouched.stream().limit(8).toList();
    }

    /**
     * Tags are model-generated, so near-misses ("bjt biasing" vs "biasing") are
     * routine. Substring containment either way is enough to treat a topic as
     * already covered — over-reporting a blind spot the student has just been
     * quizzed on is the more damaging error.
     */
    private static boolean isCovered(String candidate, Set<String> touched) {
        if (candidate.isEmpty()) {
            return true;
        }
        return touched.stream()
                .anyMatch(seen -> !seen.isEmpty()
                        && (seen.equals(candidate) || seen.contains(candidate) || candidate.contains(seen)));
    }

    /**
     * Every question this student was asked on one topic, newest first.
     *
     * <p>A mastery percentage says how well someone did; it cannot say what they
     * got wrong. This can — and getting a question back in front of the student,
     * with their own answer beside the right one, is the only part of exam prep
     * that actually teaches anything.
     *
     * <p>Assembled across two tables. Attempts record the answer and the topic;
     * quizzes hold the prompt and options. The quizzes are fetched in one batch
     * rather than per answer, because a well-drilled student has twenty attempts
     * and that would be twenty round trips to render one screen.
     *
     * @param courseCode optional filter, so opening a topic from one course's
     *                   card does not show answers from another course that
     *                   happened to use the same tag
     */
    @Transactional(readOnly = true)
    public List<TopicAnswerResponse> topicAnswers(UUID userId, String topic, String courseCode) {
        String wanted = normaliseTag(topic);
        if (wanted.isEmpty()) {
            return List.of();
        }

        String courseKey = courseCode == null ? null : CourseCodes.normalise(courseCode);

        /*
         * Which course an answer belongs to is a property of its lecture.
         *
         * A quiz taken on a single lecture carries no course code — the attempt
         * stores quiz.getCourseCode(), which is null there. Filtering on that
         * strictly showed nothing; ignoring it when absent showed everything,
         * including another course's questions. Neither is right, because the
         * attempt is simply the wrong place to ask.
         *
         * The lecture knows. Every answer records the lecture it came from, so
         * resolving lecture to course gives the true answer for both kinds of
         * quiz — including a course-wide quiz, where individual questions come
         * from different lectures.
         */
        Map<UUID, String> courseByLecture = new HashMap<>();
        if (courseKey != null) {
            transcriptionClient.listLectures(userId).forEach(lecture ->
                    courseByLecture.put(lecture.id(), CourseCodes.normalise(lecture.courseCode())));
        }

        List<QuizAttempt> attempts = attemptRepository.findByUserIdOrderByCompletedAtDesc(userId);

        if (attempts.isEmpty()) {
            return List.of();
        }

        Set<UUID> quizIds = attempts.stream().map(QuizAttempt::getQuizId).collect(Collectors.toSet());
        Map<UUID, Quiz> quizzes = quizRepository.findAllById(quizIds).stream()
                .collect(Collectors.toMap(Quiz::getId, quiz -> quiz));

        List<TopicAnswerResponse> out = new ArrayList<>();

        for (QuizAttempt attempt : attempts) {
            Quiz quiz = quizzes.get(attempt.getQuizId());
            if (quiz == null || attempt.getAnswers() == null) {
                // The quiz was deleted but its attempt survives. The score still
                // counts; the questions are simply no longer recoverable.
                continue;
            }

            Map<String, QuizQuestion> byId = quiz.getQuestions().stream()
                    .collect(Collectors.toMap(QuizQuestion::id, question -> question, (a, b) -> a));

            for (GradedAnswer answer : attempt.getAnswers()) {
                if (!wanted.equals(normaliseTag(answer.topicTag()))) {
                    continue;
                }

                if (courseKey != null) {
                    // The answer's own lecture first; the attempt's course code
                    // only as a fallback for answers written before questions
                    // carried a lecture id.
                    String answerCourse = answer.lectureId() != null
                            ? courseByLecture.get(answer.lectureId())
                            : CourseCodes.normalise(attempt.getCourseCode());

                    if (!courseKey.equals(answerCourse)) {
                        continue;
                    }
                }

                QuizQuestion question = byId.get(answer.questionId());
                if (question == null) {
                    continue;
                }

                out.add(new TopicAnswerResponse(
                        answer.questionId(),
                        question.prompt(),
                        question.options(),
                        answer.selectedIndex(),
                        answer.correctIndex(),
                        answer.correct(),
                        // The attempt's explanation is the one the student was
                        // shown; the quiz may have been regenerated since.
                        answer.explanation() != null ? answer.explanation() : question.explanation(),
                        answer.lectureId(),
                        quiz.getTitle(),
                        attempt.getCompletedAt()));
            }
        }

        return out;
    }

    private static String normaliseTag(String tag) {
        return tag == null ? "" : tag.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Pulls question frequencies from the query service into local analytics so
     * mastery reflects "I keep having to look this up", not just quiz accuracy.
     */
    private void syncQueryInsights(UUID userId) {
        for (QueryInsightsClient.TopicInsight insight : queryInsightsClient.topicInsights(userId)) {
            if (insight.topic() == null || insight.topic().isBlank()) {
                continue;
            }

            // A query count is not a fact about one lecture. "I keep looking up
            // normalisation" says the student is shaky on it wherever it is
            // taught, so it applies to every lecture carrying that topic —
            // damping mastery in all of them rather than arbitrarily one.
            List<TopicAnalytics> rows = analyticsRepository
                    .findByUserIdAndTopicTag(userId, insight.topic());

            if (rows.isEmpty()) {
                // Nothing has been quizzed on this topic yet. Hold the signal
                // on a lecture-less row until a quiz gives it a home.
                rows = List.of(new TopicAnalytics(userId, null, insight.topic()));
            }

            for (TopicAnalytics analytics : rows) {
                analytics.recordQueries((int) insight.queryCount(), insight.lastAsked());
                analyticsRepository.save(analytics);
            }
        }
    }

    private List<String> weakTopicNames(UUID userId) {
        return analyticsRepository
                .findByUserIdAndAttemptCountGreaterThanOrderByMasteryScoreAsc(userId, 0).stream()
                .filter(topic -> topic.getMasteryScore() < WEAK_THRESHOLD)
                .map(TopicAnalytics::getTopicTag)
                // One row per lecture now, so the same tag can appear several
                // times; five slots of the same topic is not a focus list.
                .distinct()
                .limit(5)
                .toList();
    }

    private static ReadinessResponse.TopicMastery toMastery(TopicAnalytics topic) {
        return new ReadinessResponse.TopicMastery(
                topic.getTopicTag(),
                (int) Math.round(topic.getMasteryScore() * 100),
                topic.getAttemptCount(),
                topic.getQueryCount(),
                topic.getLastQueried());
    }

    private Quiz requireQuiz(UUID userId, UUID quizId) {
        return quizRepository.findByIdAndUserId(quizId, userId)
                .orElseThrow(() -> ApiException.notFound("Quiz not found."));
    }
}
