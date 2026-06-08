package com.yizhaoqi.roboknow.agent;

import com.yizhaoqi.roboknow.client.OpenAiClient;
import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnswerGroundingService {

    static final String NO_EVIDENCE_ANSWER =
        "No relevant information available. The knowledge base did not return evidence for this question.";

    private final OpenAiClient openAiClient;

    public AnswerGroundingService(OpenAiClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    public String groundAnswer(String question, String draftAnswer, List<String> observations) {
        return groundAnswerInternal(question, draftAnswer, observations, null);
    }

    public String groundAnswer(String question, String draftAnswer, List<String> observations, AiUsageMetadata usageMetadata) {
        return groundAnswerInternal(question, draftAnswer, observations, usageMetadata);
    }

    private String groundAnswerInternal(String question, String draftAnswer, List<String> observations, AiUsageMetadata usageMetadata) {
        if (!hasRetrievedEvidence(observations)) {
            // No knowledge-base evidence was retrieved — never expose LLM's own knowledge or
            // hallucinations as if they came from the knowledge base. Always return the sentinel
            // so the caller knows the knowledge base had no relevant content.
            return NO_EVIDENCE_ANSWER;
        }

        String evidence = observations.stream()
            .filter(this::isEvidenceObservation)
            .collect(Collectors.joining("\n\n"));

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content",
                "You are a strict answer verifier for a RAG system. Rewrite the draft answer using ONLY facts explicitly stated in the retrieved evidence. "
                    + "IMPORTANT: Do NOT treat claims made by the user in their question as verified facts. "
                    + "If the user says 'I have X' or 'there should be Y', you must find X or Y in the retrieved evidence before including it. "
                    + "If a claim is not in the retrieved evidence, omit it entirely — do not say 'the second X is not in evidence'. Just omit. "
                    + "Preserve source citations such as (Source #1: filename). If the evidence is insufficient, answer exactly: "
                    + "No relevant information available. The retrieved evidence does not support an answer to this question."),
            Map.of("role", "user", "content",
                "Question:\n" + question
                    + "\n\nDraft answer:\n" + draftAnswer
                    + "\n\nRetrieved evidence:\n" + evidence)
        );

        String verifiedAnswer = usageMetadata == null
            ? openAiClient.chatBlocking(messages)
            : openAiClient.chatBlocking(messages, usageMetadata);
        if (verifiedAnswer == null || verifiedAnswer.isBlank()) {
            return draftAnswer;
        }
        return verifiedAnswer.trim();
    }

    private boolean hasRetrievedEvidence(List<String> observations) {
        return observations != null && observations.stream().anyMatch(this::isEvidenceObservation);
    }

    private boolean isEvidenceObservation(String observation) {
        return observation != null
            && observation.contains("Found ")
            && observation.contains("relevant document chunk");
    }
}
