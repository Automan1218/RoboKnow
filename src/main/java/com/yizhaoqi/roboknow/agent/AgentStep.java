package com.yizhaoqi.roboknow.agent;

public class AgentStep {

    private final int iteration;
    public String thought;
    public String action;
    public String actionInput;
    public String finalAnswer;
    public boolean isFinalAnswer;

    public AgentStep(int iteration) {
        this.iteration = iteration;
    }

    public int getIteration() {
        return iteration;
    }

    public String formatAssistantContent() {
        if (isFinalAnswer) {
            return "Thought: " + nvl(thought) + "\nFinal Answer: " + nvl(finalAnswer);
        }
        return "Thought: " + nvl(thought) + "\nAction: " + nvl(action) + "\nAction Input: " + nvl(actionInput);
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
