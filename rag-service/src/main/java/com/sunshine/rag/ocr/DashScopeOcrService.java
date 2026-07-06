package com.sunshine.rag.ocr;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.parser.ParseProgressListener;
import com.sunshine.rag.config.RagOcrProperties;
import com.sunshine.rag.config.RagWebClientFactory;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DashScope Qwen-VL-OCR — 扫描件 / 文本层不足时按页 OCR，页间仅空行拼接（不落页码标记） */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeOcrService {

    private static final String GENERATION_PATH =
            "/services/aigc/multimodal-generation/generation";

    private final RagOcrProperties ocrProperties;
    private final RagWebClientFactory webClientFactory;

    private WebClient client() {
        return webClientFactory.create(ocrProperties.getBaseUrl(), Duration.ofSeconds(ocrProperties.getTimeoutSeconds()))
                .mutate()
                .defaultHeader("Authorization", "Bearer " + resolveApiKey())
                .build();
    }

    public String ocrPdf(byte[] pdfBytes) {
        return ocrPdf(pdfBytes, null);
    }

    public String ocrPdf(byte[] pdfBytes, ParseProgressListener progress) {
        requireEnabled();
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            int total = doc.getNumberOfPages();
            int pages = Math.min(total, Math.max(1, ocrProperties.getMaxPages()));
            PDFRenderer renderer = new PDFRenderer(doc);
            List<String> sections = new ArrayList<>();
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, ocrProperties.getRenderDpi());
                String pageText = ocrImage(image, "document_parsing");
                if (!StringUtils.hasText(pageText)) {
                    continue;
                }
                sections.add(pageText.strip());
                if (progress != null) {
                    progress.onProgress(i + 1, pages, (i + 1) * 100.0 / pages);
                }
            }
            if (sections.isEmpty()) {
                throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
            }
            log.info("[RAG] PDF OCR 完成: pages={}/{}", pages, total);
            return String.join("\n\n", sections);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[RAG] PDF OCR 失败: {}", e.getMessage());
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
    }

    private String ocrImage(BufferedImage image, String task) {
        String dataUri = toPngDataUri(image);
        Map<String, Object> imageContent = new LinkedHashMap<>();
        imageContent.put("image", dataUri);
        imageContent.put("enable_rotate", true);
        Map<String, Object> body = Map.of(
                "model", ocrProperties.getModel(),
                "input", Map.of(
                        "messages", List.of(
                                Map.of(
                                        "role", "user",
                                        "content", List.of(imageContent)))),
                "parameters", Map.of(
                        "ocr_options", Map.of("task", task)));
        @SuppressWarnings("unchecked")
        Map<String, Object> response = client().post()
                .uri(GENERATION_PATH)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(ocrProperties.getTimeoutSeconds()))
                .block();
        return extractOcrText(response);
    }

    @SuppressWarnings("unchecked")
    static String extractOcrText(Map<String, Object> response) {
        if (response == null) {
            return "";
        }
        Object outputObj = response.get("output");
        if (!(outputObj instanceof Map<?, ?> output)) {
            return "";
        }
        Object choicesObj = output.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return "";
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return "";
        }
        Object messageObj = choice.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            return "";
        }
        Object contentObj = message.get("content");
        if (!(contentObj instanceof List<?> contents) || contents.isEmpty()) {
            return "";
        }
        Object block = contents.get(0);
        if (block instanceof Map<?, ?> map && map.get("text") != null) {
            return map.get("text").toString();
        }
        return block != null ? block.toString() : "";
    }

    private static String toPngDataUri(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "data:image/png;base64," + b64;
        } catch (Exception e) {
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
    }

    private void requireEnabled() {
        if (!ocrProperties.isEnabled() || !StringUtils.hasText(resolveApiKey())) {
            throw new BizException(RagErrorCode.OCR_NOT_CONFIGURED);
        }
    }

    private String resolveApiKey() {
        return ocrProperties.getApiKey() != null ? ocrProperties.getApiKey().strip() : "";
    }
}
