package com.sunshine.rag.ocr;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/** PDF 电子版文本层抽取（pdfbox） */
@Slf4j
@Component
public class PdfTextExtractor {

    public Optional<String> extractTextLayer(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return Optional.empty();
        }
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(text.strip());
        } catch (IOException e) {
            log.warn("[RAG] PDF 文本层抽取失败: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
