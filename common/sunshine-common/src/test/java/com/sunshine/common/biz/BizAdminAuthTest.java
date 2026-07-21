package com.sunshine.common.biz;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BizAdminAuthTest {

    @Test
    void require_matchingToken_passes() {
        BizAdminAuth.require("sunshine-biz-admin-dev", "sunshine-biz-admin-dev");
    }

    @Test
    void require_blankOrMismatch_unauthorized() {
        assertThatThrownBy(() -> BizAdminAuth.require(null, "secret"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> BizAdminAuth.require(" ", "secret"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BizAdminAuth.require("wrong", "secret"))
                .isInstanceOf(BizException.class);
        assertThat(BizAdminAuth.HEADER).isEqualTo("X-Admin-Token");
        assertThat(BizAdminAuth.DEFAULT_TOKEN).isEqualTo("sunshine-biz-admin-dev");
    }
}
