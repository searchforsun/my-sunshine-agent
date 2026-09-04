package com.sunshine.chatimage;

import com.sunshine.chatimage.config.ChatImageProperties;
import com.sunshine.chatimage.storage.ChatImageStorage;
import com.sunshine.common.core.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatImageStorageTest {

    private final ChatImageStorage storage = new ChatImageStorage(props());

    private static ChatImageProperties props() {
        ChatImageProperties p = new ChatImageProperties();
        p.getMinio().setPublicEndpoint("http://ecs4c16g:9000");
        return p;
    }

    @Test
    void validateRejectsOversize() {
        // maxBytes 临时调小模拟超限
        ChatImageProperties p = props();
        p.setMaxBytes(10);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[11]);
        assertThatThrownBy(() -> new ChatImageStorage(p).validate(file, 11))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过大小限制");
    }

    @Test
    void validateRejectsNonImage() {
        MockMultipartFile file = new MockMultipartFile("file", "a.exe", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[1]);
        assertThatThrownBy(() -> storage.validate(file, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的图片类型");
    }

    @Test
    void objectKeyUsesTenantAndDate() {
        String key = storage.objectKey("default", "a.png");
        assertThat(key).matches("default/\\d{8}/[0-9a-f-]{36}\\.png");
    }

    @Test
    void publicUrlComposesBucketAndKey() {
        String url = storage.publicUrl("default/20260904/x.png");
        assertThat(url).isEqualTo("http://ecs4c16g:9000/sunshine-chat-images/default/20260904/x.png");
    }
}
