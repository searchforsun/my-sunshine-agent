package com.sunshine.orchestrator.prompt;

import io.agentscope.core.message.Msg;

import java.util.List;
import java.util.Map;

/** ReAct 输入 + 静态层分组 token 快照（键：system/rules/skills/contextLayers；仅展示用） */
public record ComposedReactInputs(List<Msg> inputs, Map<String, Integer> staticGroups) {
}
