package com.sunshine.orchestrator.execution.retry;

import org.springframework.stereotype.Component;

import java.util.Locale;

/** 从异常或错误文案推断错误类别 */
@Component
public class ExecutionErrorClassifier {

    public ExecutionErrorClass classify(Throwable error) {
        if (error == null) {
            return ExecutionErrorClass.UNKNOWN;
        }
        return classifyMessage(error.getMessage());
    }

    public ExecutionErrorClass classifyMessage(String message) {
        if (message == null || message.isBlank()) {
            return ExecutionErrorClass.UNKNOWN;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "timeout", "timed out", "超时")) {
            return ExecutionErrorClass.TIMEOUT;
        }
        if (containsAny(lower, "circuit", "熔断")) {
            return ExecutionErrorClass.CIRCUIT_OPEN;
        }
        if (containsAny(lower, "connection refused", "connect", "503", "502", "504",
                "service unavailable", "不可用", "服务异常")) {
            return ExecutionErrorClass.SERVICE_UNAVAILABLE;
        }
        if (containsAny(lower, "缺少", "missing", "invalid", "非法", "未知工具", "未知 skill",
                "参数", "validation", "bad request", "400")) {
            return ExecutionErrorClass.VALIDATION;
        }
        if (containsAny(lower, "工具调用失败", "调用失败", "未找到", "not found", "403", "404")) {
            return ExecutionErrorClass.BUSINESS;
        }
        return ExecutionErrorClass.UNKNOWN;
    }

    public boolean isRetryable(ExecutionErrorClass errorClass, java.util.Set<String> retryOn) {
        if (errorClass == null) {
            return false;
        }
        if (retryOn != null && !retryOn.isEmpty()) {
            return retryOn.contains(errorClass.name());
        }
        return errorClass.retryableByDefault();
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
