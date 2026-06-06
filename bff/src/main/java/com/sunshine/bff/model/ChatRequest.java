package com.sunshine.bff.model;

import lombok.Data;

@Data
public class ChatRequest {

    private String content;

    private String conversationId;
}
