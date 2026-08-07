package com.sunshine.hr.model;

/** 用户假期余额（年假 / 青松假 / 调休）。 */
public record LeaveBalance(
        int year,
        int annual,
        int qingsong,
        int compensatory
) {
}
