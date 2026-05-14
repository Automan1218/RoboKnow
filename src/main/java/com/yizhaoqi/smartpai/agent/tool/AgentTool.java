package com.yizhaoqi.smartpai.agent.tool;

import com.yizhaoqi.smartpai.agent.AgentContext;

public interface AgentTool {

    String name();

    String description();

    String execute(String input, AgentContext context);
}
