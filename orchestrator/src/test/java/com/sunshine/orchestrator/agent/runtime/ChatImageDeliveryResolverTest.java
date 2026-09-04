package com.sunshine.orchestrator.agent.runtime;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 图片交付转换：url 模式透传 / base64 模式拉取编码 / 白名单拒绝 / 拉取失败降级占位 */
class ChatImageDeliveryResolverTest {

    private static final byte[] IMAGE_BYTES =
            new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n'};

    static HttpServer server;
    static String serverBase;
    static ChatImageDeliveryResolver resolver;

    @BeforeAll
    static void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            boolean fail = exchange.getRequestURI().getPath().endsWith("/fail.png");
            int status = fail ? 500 : 200;
            byte[] body = fail ? new byte[0] : IMAGE_BYTES;
            if (!fail) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        serverBase = "http://localhost:" + server.getAddress().getPort();
        resolver = new ChatImageDeliveryResolver("url", serverBase);
    }

    @AfterAll
    static void tearDown() {
        server.stop(0);
    }

    @Test
    void urlModeProducesUrlSource() {
        List<ContentBlock> blocks = resolver
                .resolveImageBlocks(List.of(serverBase + "/sunshine-chat-images/a.png"))
                .collectList().block();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ImageBlock.class);
        assertThat(((ImageBlock) blocks.get(0)).getSource()).isInstanceOf(URLSource.class);
    }

    @Test
    void base64ModeFetchesAndEncodes() {
        ChatImageDeliveryResolver r = new ChatImageDeliveryResolver("base64", serverBase);
        List<ContentBlock> blocks = r.resolveImageBlocks(
                List.of(serverBase + "/sunshine-chat-images/a.png"))
                .collectList().block();
        ImageBlock img = (ImageBlock) blocks.get(0);
        Base64Source src = (Base64Source) img.getSource();
        assertThat(src.getMediaType()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(src.getData())).isEqualTo(IMAGE_BYTES);
    }

    @Test
    void rejectUrlOutsideAllowedPrefix() {
        List<ContentBlock> blocks = resolver
                .resolveImageBlocks(List.of("http://evil.internal/secret.png"))
                .collectList().block();
        // 非白名单 URL 全部模式拒绝 → 占位文本块
        assertThat(blocks.get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) blocks.get(0)).getText())
                .isEqualTo(ChatImageDeliveryResolver.PLACEHOLDER_TEXT);
    }

    @Test
    void fetchFailureDegradesToPlaceholder() {
        ChatImageDeliveryResolver r = new ChatImageDeliveryResolver("base64", serverBase);
        List<ContentBlock> blocks = r.resolveImageBlocks(
                List.of(serverBase + "/fail.png"))
                .collectList().block();
        assertThat(blocks.get(0)).isNotInstanceOf(ImageBlock.class);
    }

    @Test
    void multipleUrlsKeepBlockOrder() {
        List<ContentBlock> blocks = resolver
                .resolveImageBlocks(List.of(
                        serverBase + "/a.png",
                        "http://evil.internal/secret.png",
                        serverBase + "/b.png"))
                .collectList().block();
        assertThat(blocks).hasSize(3);
        assertThat(((ImageBlock) blocks.get(0)).getSource())
                .isInstanceOf(URLSource.class);
        assertThat(blocks.get(1)).isInstanceOf(TextBlock.class);
        assertThat(((ImageBlock) blocks.get(2)).getSource())
                .isInstanceOf(URLSource.class);
    }

    @Test
    void emptyOrNullYieldsEmptyFlux() {
        assertThat(resolver.resolveImageBlocks(List.of()).collectList().block()).isEmpty();
        assertThat(resolver.resolveImageBlocks(null).collectList().block()).isEmpty();
    }
}
