package com.sunshine.chatimage.controller;

import com.sunshine.chatimage.exception.ChatImageErrorCode;
import com.sunshine.chatimage.storage.ChatImageStorage;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 聊天图片上传（gateway 鉴权后 x-user-id 注入） */
@RestController
@RequestMapping("/api/chat/images")
@RequiredArgsConstructor
public class ChatImageController {

    private final ChatImageStorage storage;

    @PostMapping
    public R<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ChatImageErrorCode.IMAGE_REQUIRED);
        }
        try {
            return R.ok(Map.of("url", storage.upload(tenantId, file)));
        } catch (BizException e) {
            // 校验类错误保真透传（类型/超限语义由 storage 层决定）
            throw e;
        } catch (Exception e) {
            throw new BizException(ChatImageErrorCode.UPLOAD_FAILED);
        }
    }
}
