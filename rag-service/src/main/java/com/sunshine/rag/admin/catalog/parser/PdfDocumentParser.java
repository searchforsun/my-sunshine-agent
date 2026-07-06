package com.sunshine.rag.admin.catalog.parser;

import com.sunshine.rag.config.RagOcrProperties;
import com.sunshine.rag.ocr.DashScopeOcrService;
import com.sunshine.rag.ocr.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** PDF：优先文本层，不足则 DashScope OCR */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfDocumentParser {

    private final PdfTextExtractor pdfTextExtractor;
    private final DashScopeOcrService dashScopeOcrService;
    private final RagOcrProperties ocrProperties;

    public String parse(MultipartFile file) throws IOException {
        return parseBytes(file.getBytes(), null);
    }

    public String parseBytes(byte[] bytes) {
        return parseBytes(bytes, null);
    }

    public String parseBytes(byte[] bytes, ParseProgressListener progress) {
        var textLayer = pdfTextExtractor.extractTextLayer(bytes);
        if (textLayer.isPresent() && textLayer.get().length() >= ocrProperties.getMinTextChars()) {
            if (progress != null) {
                progress.onProgress(1, 1, 100.0);
            }
            log.info("[RAG] PDF 使用文本层: chars={}", textLayer.get().length());
            return textLayer.get();
        }
        log.info("[RAG] PDF 文本层不足，走 DashScope OCR");
        return dashScopeOcrService.ocrPdf(bytes, progress);
    }
}
