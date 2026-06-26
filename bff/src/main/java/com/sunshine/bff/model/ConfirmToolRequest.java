package com.sunshine.bff.model;

import lombok.Data;

@Data
public class ConfirmToolRequest {
    private String token;
    private boolean approved;
}
