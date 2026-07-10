package com.sunshine.tools.sdk.dto;

public record SdkToolInvokeResponse(boolean ok, String result, String error) {

    public static SdkToolInvokeResponse success(String result) {
        return new SdkToolInvokeResponse(true, result, null);
    }

    public static SdkToolInvokeResponse failure(String error) {
        return new SdkToolInvokeResponse(false, null, error);
    }
}
