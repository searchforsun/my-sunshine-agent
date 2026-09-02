package com.sunshine.hr.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** hr_leave_balance 复合主键：(tenant_id, user_id, year) */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class HrLeaveBalanceId implements Serializable {

    private String tenantId;
    private String userId;
    private Integer year;

    public HrLeaveBalanceId(String tenantId, String userId, Integer year) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.year = year;
    }
}
