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
    String outputSummaryKind() default "truncate";
}
