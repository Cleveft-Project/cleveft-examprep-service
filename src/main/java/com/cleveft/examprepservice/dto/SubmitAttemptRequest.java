package com.cleveft.examprepservice.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SubmitAttemptRequest(

        @NotEmpty(message = "Answer at least one question before submitting")
        List<SubmittedAnswer> answers
) {

    public record SubmittedAnswer(String questionId, Integer selectedIndex) {
    }
}
