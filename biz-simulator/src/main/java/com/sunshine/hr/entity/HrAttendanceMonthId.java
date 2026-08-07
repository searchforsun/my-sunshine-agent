package com.sunshine.hr.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** hr_attendance_month 复合主键：(tenant_id, user_id, year_month) */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class HrAttendanceMonthId implements Serializable {

    private String tenantId;
    private String userId;
    private String yearMonth;

    public HrAttendanceMonthId(String tenantId, String userId, String yearMonth) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.yearMonth = yearMonth;
    }
}
