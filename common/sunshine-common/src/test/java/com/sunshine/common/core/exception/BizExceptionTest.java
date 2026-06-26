package com.sunshine.common.core.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BizExceptionTest {

    @Test
    void carriesErrorCode() {
        BizException ex = new BizException(CommonErrorCode.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("请求的内容不存在");
    }

    @Test
    void fixedErrorCode_implementsMessage() {
        FixedErrorCode code = new FixedErrorCode(409, "remote_conflict", "数据冲突，请刷新后重试");
        assertThat(code.getMessage()).isEqualTo("数据冲突，请刷新后重试");
        BizException ex = new BizException(code);
        assertThat(ex.getErrorCode().getKey()).isEqualTo("remote_conflict");
    }

    @Test
    void legacyStringConstructor_removed() {
        assertThatThrownBy(() -> BizException.class.getConstructor(String.class))
                .isInstanceOf(NoSuchMethodException.class);
    }
}
