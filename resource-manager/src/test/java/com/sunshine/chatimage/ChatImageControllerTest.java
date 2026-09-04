package com.sunshine.chatimage;

import com.sunshine.chatimage.controller.ChatImageController;
import com.sunshine.chatimage.controller.ChatImageUploadAdvice;
import com.sunshine.chatimage.exception.ChatImageErrorCode;
import com.sunshine.chatimage.storage.ChatImageStorage;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller 薄层契约：空文件拒绝、url 透传、校验错误码语义保真（standalone MockMvc，不起 Spring 上下文） */
@ExtendWith(MockitoExtension.class)
class ChatImageControllerTest {

    @Mock
    private ChatImageStorage storage;

    private ChatImageController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ChatImageController(storage);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new ChatImageUploadAdvice())
                .build();
    }

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);
        mockMvc.perform(multipart("/api/chat/images").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorKey").value("chat_image_required"));
        verifyNoInteractions(storage);
    }

    @Test
    void uploadReturnsUrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1});
        when(storage.upload(eq("default"), any())).thenReturn(
                "http://ecs4c16g:9000/sunshine-chat-images/default/20260904/x.png");
        mockMvc.perform(multipart("/api/chat/images").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value(
                        "http://ecs4c16g:9000/sunshine-chat-images/default/20260904/x.png"));
    }

    @Test
    void uploadMapsOversizeToBusinessCode() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1});
        when(storage.upload(any(), any())).thenThrow(new BizException(ChatImageErrorCode.IMAGE_TOO_LARGE));
        mockMvc.perform(multipart("/api/chat/images").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("chat_image_too_large"));
    }

    @Test
    void uploadMapsUnsupportedTypeToBusinessCode() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.exe", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1});
        when(storage.upload(any(), any())).thenThrow(new BizException(ChatImageErrorCode.IMAGE_TYPE_UNSUPPORTED));
        mockMvc.perform(multipart("/api/chat/images").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("chat_image_type_unsupported"));
    }

    @Test
    void multipartOverflowMapsToTooLarge() {
        // multipart 解析先于 handler mapping，经独立 advice 映射为业务超限语义
        com.sunshine.common.core.result.R<Void> body =
                new ChatImageUploadAdvice().onMultipartOverflow(new MaxUploadSizeExceededException(10)).getBody();
        assertThat(body.getCode()).isEqualTo(400);
        assertThat(body.getErrorKey()).isEqualTo("chat_image_too_large");
    }
}
