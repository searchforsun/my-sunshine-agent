package com.sunshine.tools.sdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SunshineTool {
    String id();
    String displayName();
    String description() default "";
    String sideEffect() default "read";
    String timelinePhase() default "tool";

    /**
     * 时间线一步摘要模板，占位符 {@code {var}}。未配置则仅展示 orchestrator Nacos steps.tool 默认 after。
     * 示例：{@code "{status} {count} 条，合计 ¥{amount}"}、{@code "{output}"}（首行原文）
     */
    String timelineSummaryTemplate() default "";

    /**
     * 占位符提取 JSON：键为变量名，值为表达式（{@code regex:...} / {@code json:path} / {@code line:n}）。
     * 示例：{@code {"count":"regex:共\\s*(\\d+)\\s*条"}}
     */
    String timelineSummaryExtract() default "";
}
