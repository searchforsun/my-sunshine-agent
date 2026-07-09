package com.sunshine.orchestrator.taskboard;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** manage_tasks merge 时 content 语义匹配 — 避免模型微调文案后重复建项 */
final class TaskBoardContentMatch {

    private static final Pattern TRAILING_ID = Pattern.compile("\\s*[\\(\\[]\\d+[\\)\\]]\\s*.*$");
    private static final Pattern BUSINESS_ID = Pattern.compile("[\\[(](\\d{3,})[\\])]|(?:^|\\s)(\\d{3,})(?:\\s|$)");
    private static final Pattern STRIP_NOISE = Pattern.compile(
            "(提交|审批|查询|处理|执行|当前|所有|待|的|财务|消息|任务|OA|pending|完成|进行)");

    private TaskBoardContentMatch() {
    }

    static String normalizeKey(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String s = content.strip();
        s = TRAILING_ID.matcher(s).replaceAll("");
        s = s.replaceAll("\\s*[—\\-].*$", "");
        s = s.replaceAll("\\d+", "");
        s = STRIP_NOISE.matcher(s).replaceAll("");
        return s.replaceAll("\\s+", "");
    }

    /** 去动词/编号后的语义指纹，用于「采购付款审批」↔「1002 采购付款」类匹配 */
    static String semanticFingerprint(String content) {
        return normalizeKey(content);
    }

    static String extractBusinessId(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        Matcher matcher = BUSINESS_ID.matcher(content.strip());
        if (matcher.find()) {
            String g1 = matcher.group(1);
            return g1 != null ? g1 : matcher.group(2);
        }
        return null;
    }

    static boolean hasConcreteIdentity(String content) {
        return extractBusinessId(content) != null;
    }

    static boolean isAbstractBatchItem(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        if (hasConcreteIdentity(content)) {
            return false;
        }
        String fp = semanticFingerprint(content);
        return fp.length() < 6 || content.contains("逐项") || content.contains("逐条")
                || content.contains("剩余") || content.contains("待处理");
    }

    static boolean fingerprintsOverlap(String a, String b) {
        String fa = semanticFingerprint(a);
        String fb = semanticFingerprint(b);
        if (!StringUtils.hasText(fa) || !StringUtils.hasText(fb)) {
            return false;
        }
        if (fa.equals(fb)) {
            return true;
        }
        String shorter = fa.length() <= fb.length() ? fa : fb;
        String longer = fa.length() > fb.length() ? fa : fb;
        return shorter.length() >= 2 && longer.contains(shorter);
    }

    static String findMatchingId(Map<String, TaskBoardItemView> map, TaskBoardItemView update) {
        if (update == null || map.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(update.id()) && map.containsKey(update.id().strip())) {
            return update.id().strip();
        }
        String exact = update.content() != null ? update.content().strip() : "";
        for (TaskBoardItemView item : map.values()) {
            if (exact.equals(item.content())) {
                return item.id();
            }
        }
        String key = normalizeKey(update.content());
        if (StringUtils.hasText(key)) {
            for (TaskBoardItemView item : map.values()) {
                if (key.equals(normalizeKey(item.content()))) {
                    return item.id();
                }
            }
        }
        String bizId = extractBusinessId(update.content());
        if (bizId != null) {
            for (TaskBoardItemView item : map.values()) {
                if (item.content() != null && item.content().contains(bizId)) {
                    return item.id();
                }
            }
        }
        String bestId = null;
        int bestScore = 0;
        for (TaskBoardItemView item : map.values()) {
            if (!fingerprintsOverlap(update.content(), item.content())) {
                continue;
            }
            int score = semanticFingerprint(item.content()).length();
            if (hasConcreteIdentity(update.content()) && !hasConcreteIdentity(item.content())) {
                score += 100;
            }
            if (score > bestScore) {
                bestScore = score;
                bestId = item.id();
            }
        }
        return bestId;
    }

    static TaskBoardItemView mergeInto(TaskBoardItemView existing, TaskBoardItemView update) {
        String content = richerContent(existing.content(), update.content());
        String status = preferStatus(existing.status(), update.status());
        return new TaskBoardItemView(existing.id(), content, status);
    }

    static List<TaskBoardItemView> dedupeBySemanticKey(List<TaskBoardItemView> items) {
        if (items == null || items.size() <= 1) {
            return items;
        }
        List<TaskBoardItemView> work = new ArrayList<>(items);
        boolean changed;
        do {
            changed = false;
            outer:
            for (int i = 0; i < work.size(); i++) {
                for (int j = i + 1; j < work.size(); j++) {
                    TaskBoardItemView a = work.get(i);
                    TaskBoardItemView b = work.get(j);
                    if (!fingerprintsOverlap(a.content(), b.content())) {
                        continue;
                    }
                    TaskBoardItemView keep = pickPrimary(a, b);
                    TaskBoardItemView drop = keep == a ? b : a;
                    work.set(work.indexOf(keep), mergeInto(keep, drop));
                    work.remove(drop);
                    changed = true;
                    break outer;
                }
            }
        } while (changed && work.size() > 1);
        return List.copyOf(work);
    }

    private static TaskBoardItemView pickPrimary(TaskBoardItemView a, TaskBoardItemView b) {
        boolean aConcrete = hasConcreteIdentity(a.content());
        boolean bConcrete = hasConcreteIdentity(b.content());
        if (aConcrete && !bConcrete) {
            return a;
        }
        if (bConcrete && !aConcrete) {
            return b;
        }
        if (statusRank(a.status()) != statusRank(b.status())) {
            return statusRank(a.status()) >= statusRank(b.status()) ? a : b;
        }
        return a.content().length() >= b.content().length() ? a : b;
    }

    private static String richerContent(String a, String b) {
        if (!StringUtils.hasText(a)) {
            return b;
        }
        if (!StringUtils.hasText(b)) {
            return a;
        }
        boolean aConcrete = hasConcreteIdentity(a);
        boolean bConcrete = hasConcreteIdentity(b);
        if (aConcrete && !bConcrete) {
            return a.strip();
        }
        if (bConcrete && !aConcrete) {
            return b.strip();
        }
        return a.strip().length() >= b.strip().length() ? a.strip() : b.strip();
    }

    private static String preferStatus(String a, String b) {
        return statusRank(a) >= statusRank(b) ? a : b;
    }

    private static int statusRank(String status) {
        if ("completed".equals(status)) {
            return 3;
        }
        if ("in_progress".equals(status)) {
            return 2;
        }
        if ("cancelled".equals(status)) {
            return 1;
        }
        return 0;
    }
}
