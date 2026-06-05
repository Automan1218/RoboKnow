package com.yizhaoqi.roboknow.agent.tool;

import com.yizhaoqi.roboknow.agent.AgentContext;

public interface AgentTool {

    String name();

    String description();

    String execute(String input, AgentContext context);
}
