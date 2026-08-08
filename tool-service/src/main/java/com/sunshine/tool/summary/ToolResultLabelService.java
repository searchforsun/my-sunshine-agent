package com.sunshine.tool.summary;

import com.sunshine.tool.config.ToolTimelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 时间线摘要文案辅助 — 读 Nacos tool.timeline.result */
@Service
@RequiredArgsConstructor
public class ToolResultLabelService {

    private final ToolTimelineProperties timelineProperties;

    public int truncateMaxChars() {
        int max = timeline().getTruncateMaxChars();
        return max > 0 ? max : 80;
    }

    public boolean isEmptyToolSummary(String summary) {
        return !StringUtils.hasText(summary);
    }

    private ToolTimelineProperties.ResultTimeline timeline() {
        ToolTimelineProperties.ResultTimeline cfg = timelineProperties.getResult();
        return cfg != null ? cfg : new ToolTimelineProperties.ResultTimeline();
    }
}
