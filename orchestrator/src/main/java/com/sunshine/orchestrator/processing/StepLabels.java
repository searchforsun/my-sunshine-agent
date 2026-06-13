package com.sunshine.orchestrator.processing;

public final class StepLabels {

    private StepLabels() {
    }

    public static String labelFor(String stepId) {
        return switch (stepId) {
            case "intent" -> "识别意图";
            case "rag" -> "检索知识库";
            case "agent" -> "分析作答";
            case "generate" -> "生成回答";
            default -> {
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "调用工具 " + toolName(stepId);
                }
                yield stepId;
            }
        };
    }

    public static String beforeFor(String stepId) {
        return switch (stepId) {
            case "intent" -> "准备识别意图";
            case "rag" -> "准备检索向量库";
            case "agent" -> "理解问题，梳理作答思路";
            case "generate" -> "组织语言，撰写回答";
            default -> {
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "准备调用 " + toolName(stepId);
                }
                yield null;
            }
        };
    }

    public static String activeFor(String stepId) {
        return switch (stepId) {
            case "intent" -> "正在分析用户输入";
            case "rag" -> "正在查询 Milvus";
            case "agent" -> "结合检索结果分析问题";
            case "generate" -> "正在生成并输出回答";
            default -> {
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "正在执行 " + toolName(stepId);
                }
                yield null;
            }
        };
    }

    public static String afterTemplate(String stepId, String detail) {
        return switch (stepId) {
            case "intent" -> detail != null ? "判定为：" + detail : "判定完成";
            case "rag" -> detail;
            case "agent" -> detail != null ? detail : "完成问题分析";
            case "generate" -> detail != null ? detail : "回答已生成";
            default -> detail;
        };
    }

    private static String toolName(String stepId) {
        return stepId.substring("tool-".length());
    }
}
