package com.sunshine.common.core.result;

import com.sunshine.common.core.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RTest {

    @Test
    void failFromErrorCode_setsMsgAndKey() {
        R<Void> r = R.fail(CommonErrorCode.BAD_REQUEST);
        assertThat(r.getCode()).isEqualTo(400);
        assertThat(r.getMsg()).isEqualTo("请求参数有误");
        assertThat(r.getErrorKey()).isEqualTo("bad_request");
    }
}
