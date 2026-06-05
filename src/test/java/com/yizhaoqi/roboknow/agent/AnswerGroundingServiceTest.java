package com.yizhaoqi.roboknow.agent;

import com.yizhaoqi.roboknow.client.OpenAiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnswerGroundingServiceTest {

    @Test
    void returnsNoRelevantInformationWhenNoEvidenceWasRetrieved() {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        AnswerGroundingService service = new AnswerGroundingService(openAiClient);

        String answer = service.groundAnswer("What did Henry study?", "Henry studied medicine.", List.of());

        assertEquals("No relevant information available. The knowledge base did not return evidence for this question.", answer);
        verify(openAiClient, never()).chatBlocking(anyList());
    }

    @Test
    void asksModelToVerifyDraftAgainstRetrievedEvidence() {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.chatBlocking(anyList())).thenReturn(
            "Henry Hou studied Software Engineering at the National University of Singapore. (Source #1: henry-hou-cv.pdf)"
        );
        AnswerGroundingService service = new AnswerGroundingService(openAiClient);

        String answer = service.groundAnswer(
            "What did Henry study?",
            "Henry studied Software Engineering.",
            List.of("Found 1 relevant document chunk:\n[Source #1] File: henry-hou-cv.pdf, Chunk: 7, Score: 0.92\nHenry Hou studied Software Engineering at NUS.")
        );

        assertTrue(answer.contains("Software Engineering"));
        assertTrue(answer.contains("Source #1"));
        verify(openAiClient).chatBlocking(anyList());
    }

    @Test
    void verifierPromptContainsQuestionDraftAndEvidence() {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        when(openAiClient.chatBlocking(anyList())).thenReturn("Verified answer");
        AnswerGroundingService service = new AnswerGroundingService(openAiClient);

        service.groundAnswer("Question", "Draft", List.of("Found 1 relevant document chunk:\nEvidence"));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<Map<String, String>>>) (Class<?>) List.class);
        verify(openAiClient).chatBlocking(captor.capture());
        String verifierInput = captor.getValue().get(1).get("content");
        assertTrue(verifierInput.contains("Question"));
        assertTrue(verifierInput.contains("Draft"));
        assertTrue(verifierInput.contains("Evidence"));
    }
}
