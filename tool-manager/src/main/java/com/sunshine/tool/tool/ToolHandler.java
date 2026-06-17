package com.sunshine.tool.tool;

import java.util.Map;

/**
 * 可注册到 ToolRegistry 的工具处理器
 */
public interface ToolHandler {

    String name();

    String invoke(Map<String, String> params);
}
