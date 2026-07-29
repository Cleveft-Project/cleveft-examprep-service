package com.cleveft.examprepservice.service;

import com.cleveft.examprepservice.ai.AiServiceException;
import com.cleveft.examprepservice.ai.GeminiJsonClient;
import com.cleveft.examprepservice.client.TranscriptionClient;
import com.cleveft.examprepservice.dto.QuizQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a lecture transcript into multiple-choice questions.
 *
 * <p>Questions are generated strictly from the transcript. A quiz drawn from the
 * model's general knowledge would test the subject rather than the lecture, and
 * the resulting score would say nothing about whether the student understood
 * what their lecturer actually taught.
 */
@Service
public class QuizGenerationService {

    private static final Logger log = LoggerFactory.getLogger(QuizGenerationService.class);
    private static final int MAX_TRANSCRIPT_CHARS = 100_000;

    private static final String INSTRUCTION = """
            You are an experienced university examiner writing multiple-choice questions from a
            lecture transcript.

            Return ONLY a JSON object of this exact shape:

            {
              "title": "...",
              "topics": ["...", "...", "..."],
              "questions": [
                {
                  "prompt": "...",
                  "options": ["...", "...", "...", "..."],
                  "correctIndex": 0,
                  "explanation": "...",
                  "topicTag": "..."
                }
              ]
            }

            Work in two steps. First read the transcript and list its topics. Then write the
            questions, tagging each with one of the topics you listed.

            Rules:
            - "topics" is 3 to 6 concepts the lecturer actually taught, named as a student
              would name them when saying what to revise. Lowercase, 1-4 words each.
              Good: "bjt biasing", "tcp handshake", "gauss's law", "vector embeddings".
              Bad: "future planning", "process outcome", "key concepts", "understanding",
              "applications", "chapter 2" — these name nothing and cannot be revised from.
              If the transcript is too thin for three real concepts, return fewer. Never pad
              the list with generic categories.
            - "topicTag" must be copied verbatim from "topics". Never invent one that is not
              in that list.
            - Every question must be answerable from the transcript alone. Never test material
              the lecturer did not cover.
            - Exactly 4 options per question. Exactly one is correct.
            - "correctIndex" is the 0-based position of the correct option.
            - Distractors must be plausible to someone who half-understands the topic. Do not
              write obviously silly options, and do not make the correct answer the longest one.
            - Vary the correct position across the quiz; do not favour any one index.
            - "explanation" says why the answer is right, referring to what the lecturer said.
            - Test understanding and application, not trivia or exact wording recall.
            """;

    private final GeminiJsonClient client;

    public QuizGenerationService(GeminiJsonClient client) {
        this.client = client;
    }

    public GeneratedQuiz generate(TranscriptionClient.LectureDetail lecture,
                                  int questionCount,
                                  String difficulty,
                                  List<String> focusTopics) {

        String transcript = lecture.fullTranscript();
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            log.warn("Transcript for lecture {} truncated to {} chars for quiz generation",
                    lecture.id(), MAX_TRANSCRIPT_CHARS);
            transcript = transcript.substring(0, MAX_TRANSCRIPT_CHARS);
        }

        StringBuilder prompt = new StringBuilder()
                .append("Lecture title: ").append(lecture.title()).append('\n');

        if (lecture.courseCode() != null) {
            prompt.append("Course: ").append(lecture.courseCode()).append('\n');
        }

        prompt.append("Write exactly ").append(questionCount)
                .append(" questions at ").append(difficulty).append(" difficulty.\n");

        if (focusTopics != null && !focusTopics.isEmpty()) {
            prompt.append("Weight the questions toward these topics, which this student is ")
                    .append("currently weakest on: ")
                    .append(String.join(", ", focusTopics))
                    .append(".\n");
        }

        prompt.append("\nTranscript:\n").append(transcript);

        JsonNode result = client.generateJson(INSTRUCTION, prompt.toString());
        return parse(result, lecture.title(), questionCount);
    }

    /**
     * Tags that name nothing.
     *
     * <p>Kept as a backstop rather than as the main defence. Asking the model
     * for a topic list first and requiring every tag to come from it is what
     * actually fixes this; a blocklist only catches the cases where it ignores
     * that too. Matching is on the whole tag, not a substring — "process
     * scheduling" is a real concept and must survive.
     */
    private static final Set<String> USELESS_TAGS = Set.of(
            "future planning", "process outcome", "key concepts", "understanding",
            "applications", "general", "overview", "introduction", "concepts",
            "summary", "miscellaneous", "other", "topics", "content");

    private GeneratedQuiz parse(JsonNode root, String lectureTitle, int expectedCount) {
        String title = root.path("title").asText(lectureTitle + " — practice quiz");

        // The topic list the model committed to before writing questions.
        // Everything a question claims has to appear here.
        Set<String> declared = new LinkedHashSet<>();
        for (JsonNode topic : root.path("topics")) {
            String value = normaliseTag(topic.asText("").trim());
            if (!value.isEmpty() && !USELESS_TAGS.contains(value)) {
                declared.add(value);
            }
        }

        List<QuizQuestion> questions = new ArrayList<>();
        for (JsonNode node : root.path("questions")) {
            QuizQuestion question = parseQuestion(node);
            if (question != null) {
                questions.add(withCheckedTag(question, declared));
            }
        }

        if (questions.isEmpty()) {
            throw new AiServiceException(
                    "Could not build a quiz from this lecture. The transcript may be too short.");
        }

        if (questions.size() < expectedCount) {
            log.info("Model produced {} usable questions of {} requested", questions.size(), expectedCount);
        }

        return new GeneratedQuiz(title, questions);
    }

    /**
     * Holds a question's tag to the list the model declared.
     *
     * <p>A tag outside that list, or one on the useless list, is dropped rather
     * than kept — a null tag simply means this question contributes no mastery
     * data, which is a small loss. Keeping it would put a meaningless label in
     * front of the student on the readiness screen and, worse, persist it as a
     * topic they appear to be weak at forever.
     */
    private QuizQuestion withCheckedTag(QuizQuestion question, Set<String> declared) {
        String tag = question.topicTag();

        if (tag != null && !tag.isBlank() && !USELESS_TAGS.contains(tag)
                && (declared.isEmpty() || declared.contains(tag))) {
            return question;
        }

        if (tag != null && !tag.isBlank()) {
            log.info("Dropping topic tag '{}' — not one of the {} declared topics", tag, declared.size());
        }

        return new QuizQuestion(
                question.id(),
                question.prompt(),
                question.options(),
                question.correctIndex(),
                question.explanation(),
                null,
                question.lectureId());
    }

    /**
     * Rejects malformed questions rather than persisting them. A question with a
     * correctIndex pointing outside its options is unanswerable and would score
     * every student zero on it forever.
     */
    private QuizQuestion parseQuestion(JsonNode node) {
        String prompt = node.path("prompt").asText("").trim();

        List<String> options = new ArrayList<>();
        for (JsonNode option : node.path("options")) {
            String value = option.asText("").trim();
            if (!value.isEmpty()) {
                options.add(value);
            }
        }

        if (prompt.isEmpty() || options.size() < 2) {
            return null;
        }

        JsonNode correctIndexNode = node.path("correctIndex");
        if (!correctIndexNode.isInt()) {
            return null;
        }

        int correctIndex = correctIndexNode.asInt();
        if (correctIndex < 0 || correctIndex >= options.size()) {
            return null;
        }

        return new QuizQuestion(
                UUID.randomUUID().toString(),
                prompt,
                options,
                correctIndex,
                node.path("explanation").asText("").trim(),
                normaliseTag(node.path("topicTag").asText("").trim()),
                // Left null here: this method only sees one lecture's JSON and
                // has no identity for it. generateQuiz stamps each question
                // with its source lecture, which is the only place that knows
                // it for a course-wide quiz.
                null);
    }

    private static String normaliseTag(String tag) {
        return tag.isEmpty() ? "general" : tag.toLowerCase();
    }

    public record GeneratedQuiz(String title, List<QuizQuestion> questions) {
    }
}
