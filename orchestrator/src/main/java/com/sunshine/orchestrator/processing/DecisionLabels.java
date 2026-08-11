package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.DecisionAnswer;
import com.sunshine.orchestrator.agent.DecisionQuestion;
import org.springframework.util.StringUtils;

import java.util.List;

/** request_decision 时间线文案静态门面 — 单测可 bind */
public final class DecisionLabels {

    private static volatile DecisionLabelService service;

    private DecisionLabels() {
    }

    public static void bind(DecisionLabelService labelService) {
        service = labelService;
    }

    public static String label() {
        return requireService().label();
    }

    public static String before() {
        return requireService().before();
    }

    public static String active(String question) {
        return requireService().active(question);
    }

    public static String after(String choice) {
        return requireService().after(choice);
    }

    /**
     * Catalog after 仍用 {@code {choice}}：将多题 answers 格式化为 choice 字符串注入（勿截断 id）。
     * 形如 {@code q1=agent; q2=perf,__custom__}；有 questions 时按题序输出。
     */
    public static String formatChoiceFromAnswers(
            List<DecisionQuestion> questions, List<DecisionAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (questions != null && !questions.isEmpty()) {
            for (DecisionQuestion question : questions) {
                if (question == null || !StringUtils.hasText(question.id())) {
                    continue;
                }
                DecisionAnswer matched = findAnswer(answers, question.id());
                if (matched == null) {
                    continue;
                }
                appendAnswerChoice(sb, matched);
            }
            return sb.toString();
        }
        for (DecisionAnswer answer : answers) {
            appendAnswerChoice(sb, answer);
        }
        return sb.toString();
    }

    private static DecisionAnswer findAnswer(List<DecisionAnswer> answers, String questionId) {
        for (DecisionAnswer answer : answers) {
            if (answer != null && questionId.equals(answer.questionId())) {
                return answer;
            }
        }
        return null;
    }

    private static void appendAnswerChoice(StringBuilder sb, DecisionAnswer answer) {
        if (answer == null || !StringUtils.hasText(answer.questionId())) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("; ");
        }
        String ids = answer.selectedOptionIds() == null
                ? ""
                : String.join(",", answer.selectedOptionIds());
        sb.append(answer.questionId().strip()).append('=').append(ids);
    }

    public static String afterFail() {
        return requireService().afterFail();
    }

    public static String afterTimeout() {
        return requireService().afterTimeout();
    }

    public static String afterCancel() {
        return requireService().afterCancel();
    }

    public static String afterSkip() {
        return requireService().afterSkip();
    }

    private static DecisionLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("DecisionLabelService 未 bind");
        }
        return service;
    }
}
