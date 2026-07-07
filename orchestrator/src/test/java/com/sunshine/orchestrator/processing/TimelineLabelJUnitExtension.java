package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** 单测自动绑定 Nacos 默认时间线模板（TD-053+ 删除静态 fallback 后的测试基建） */
public final class TimelineLabelJUnitExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        TimelineLabelTestSupport.bindDefaults();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TimelineLabelTestSupport.unbind();
    }
}
