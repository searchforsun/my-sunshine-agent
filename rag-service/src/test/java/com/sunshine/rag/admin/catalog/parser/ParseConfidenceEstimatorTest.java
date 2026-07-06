package com.sunshine.rag.admin.catalog.parser;

import com.sunshine.rag.admin.catalog.DocumentSourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParseConfidenceEstimatorTest {

    @Test
    void shortPdfContentGoesQuarantine() {
        var result = ParseConfidenceEstimator.estimate("abc", DocumentSourceType.PDF, 0.65);
        assertThat(result.autoPass()).isFalse();
        assertThat(result.confidence()).isLessThan(0.65);
    }

    @Test
    void normalDocxAutoPasses() {
        String body = "测试11段落内容说明。\n\n测试22段落内容说明。\n\n测试33段落内容说明。";
        var result = ParseConfidenceEstimator.estimate(body, DocumentSourceType.DOCX, 0.65);
        assertThat(result.autoPass()).isTrue();
    }
}
