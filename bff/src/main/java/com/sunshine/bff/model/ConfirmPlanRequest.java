package com.sunshine.bff.model;

import lombok.Data;

@Data
public class ConfirmPlanRequest {
    private String token;
    private String action;
    private String modificationHint;
}
