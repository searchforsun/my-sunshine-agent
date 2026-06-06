package com.sunshine.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 文档分段器
 */
@Slf4j
@Component
public class MarkdownParser {

    private static final int CHUNK_SIZE = 400;
    private static final int OVERLAP = 80;

    public List<String> parse(String markdown) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = markdown.split("\n\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (current.length() + trimmed.length() > CHUNK_SIZE
                    && current.length() > 0) {
                chunks.add(current.toString().trim());
                String overlap = current.length() > OVERLAP
                        ? current.substring(current.length() - OVERLAP)
                        : current.toString();
                current = new StringBuilder(overlap);
            }

            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        log.info("[RAG] Markdown 分段: {} chars → {} chunks",
                markdown.length(), chunks.size());
        return chunks;
    }
}
