package com.sunshine.common.web;

import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.exception.FixedErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeHttpStatusTest {

    @Test
    void mapsStandardHttpCodes() {
        assertThat(ErrorCodeHttpStatus.of(CommonErrorCode.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCodeHttpStatus.of(CommonErrorCode.UNAUTHORIZED)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ErrorCodeHttpStatus.of(CommonErrorCode.GONE)).isEqualTo(HttpStatus.GONE);
        assertThat(ErrorCodeHttpStatus.of(CommonErrorCode.INTERNAL_ERROR)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void mapsDownstreamFixedCode() {
        FixedErrorCode remote = new FixedErrorCode(409, "remote_conflict", "数据冲突");
        assertThat(ErrorCodeHttpStatus.of(remote)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void nonStandardCodeFallsBackByRange() {
        FixedErrorCode custom = new FixedErrorCode(600, "custom", "自定义");
        assertThat(ErrorCodeHttpStatus.of(custom)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        FixedErrorCode client = new FixedErrorCode(460, "client", "客户端");
        assertThat(ErrorCodeHttpStatus.of(client)).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
