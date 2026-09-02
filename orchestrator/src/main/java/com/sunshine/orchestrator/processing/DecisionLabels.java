package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.DecisionAnswer;
import com.sunshine.orchestrator.agent.DecisionOption;
import com.sunshine.orchestrator.agent.DecisionQuestion;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
     * Catalog after 用 {@code {choice}}：将多题 answers 格式化为用户可读的 choice 字符串注入。
     * 展示选项 label（非内部 id），自定义项展示手写内容；多题按题序以「；」分隔，
     * 题内多选以「、」分隔。无题元数据兜底时仅输出选中项列表，避免暴露 questionId。
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
                appendAnswerChoice(sb, question, matched);
            }
            return sb.toString();
        }
        for (DecisionAnswer answer : answers) {
            appendAnswerChoice(sb, null, answer);
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

    private static void appendAnswerChoice(StringBuilder sb, DecisionQuestion question, DecisionAnswer answer) {
        if (answer == null || !StringUtils.hasText(answer.questionId())) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('；');
        }
        List<String> selected = answer.selectedOptionIds() == null
                ? List.of()
                : answer.selectedOptionIds();
        if (question == null || question.options() == null || question.options().isEmpty()) {
            // 无题元数据：仅输出手写内容，避免暴露内部 option id
            List<String> parts = new ArrayList<>();
            if (selected.contains(DecisionOption.CUSTOM_ID)) {
                parts.add(customLabel(answer));
            }
            sb.append(String.join("、", parts));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (String id : selected) {
            if (DecisionOption.CUSTOM_ID.equals(id)) {
                labels.add(customLabel(answer));
                continue;
            }
            String label = question.options().stream()
                    .filter(o -> o != null && id.equals(o.id()))
                    .map(DecisionOption::label)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .map(String::strip)
                    .orElseGet(() -> StringUtils.hasText(id) ? id.strip() : "");
            if (StringUtils.hasText(label)) {
                labels.add(label);
            }
        }
        sb.append(String.join("、", labels));
    }

    private static String customLabel(DecisionAnswer answer) {
        return StringUtils.hasText(answer.customInput())
                ? answer.customInput().strip()
                : "自定义";
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
