package com.sunshine.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档分段器：按标题/表格/代码块等语义单元切分，避免句中与表格行内截断。
 */
@Slf4j
@Component
public class MarkdownParser {

    private static final int MAX_CHUNK_SIZE = 1200;
    private static final Pattern HEADER = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?\\n]");

    public List<String> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        markdown = markdown.replace("\r\n", "\n").replace('\r', '\n');

        List<Block> blocks = tokenize(markdown);
        List<String> chunks = packIntoChunks(blocks);

        log.info("[RAG] Markdown 分段: {} chars → {} chunks", markdown.length(), chunks.size());
        return chunks;
    }

    private List<Block> tokenize(String markdown) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            String line = lines[i].replace("\r", "");

            if (line.trim().startsWith("```")) {
                blocks.add(new CodeBlock(readCodeFence(lines, i)));
                i = skipCodeFence(lines, i);
                continue;
            }

            Matcher headerMatcher = HEADER.matcher(line);
            if (headerMatcher.matches()) {
                int level = headerMatcher.group(1).length();
                blocks.add(new HeaderBlock(level, line.trim()));
                i++;
                continue;
            }

            if (isTableRow(line)) {
                StringBuilder table = new StringBuilder(line.trim());
                i++;
                while (i < lines.length && isTableRow(lines[i])) {
                    table.append('\n').append(lines[i].trim());
                    i++;
                }
                blocks.add(new TableBlock(table.toString()));
                continue;
            }

            if (line.isBlank()) {
                i++;
                continue;
            }

            StringBuilder paragraph = new StringBuilder(line.trim());
            i++;
            while (i < lines.length && !lines[i].isBlank()
                    && !lines[i].trim().startsWith("```")
                    && !HEADER.matcher(lines[i]).matches()
                    && !isTableRow(lines[i])) {
                paragraph.append('\n').append(lines[i].trim());
                i++;
            }
            blocks.add(new ParagraphBlock(paragraph.toString()));
        }

        return blocks;
    }

    private List<String> packIntoChunks(List<Block> blocks) {
        Deque<HeaderEntry> headerStack = new ArrayDeque<>();
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (Block block : blocks) {
            if (block instanceof HeaderBlock header) {
                if (header.level() <= 2 && !current.isEmpty()) {
                    chunks.add(finalizeChunk(current, headerStack));
                    current = new StringBuilder();
                }
                updateHeaderStack(headerStack, header);
                appendToChunk(current, header.text());
                continue;
            }

            List<String> pieces = block instanceof ParagraphBlock
                    ? splitLargeText(block.text(), MAX_CHUNK_SIZE)
                    : List.of(block.text());

            for (String piece : pieces) {
                if (!current.isEmpty() && current.length() + piece.length() + 2 > MAX_CHUNK_SIZE) {
                    chunks.add(finalizeChunk(current, headerStack));
                    current = new StringBuilder();
                }
                appendToChunk(current, piece);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(finalizeChunk(current, headerStack));
        }
        return chunks;
    }

    private void updateHeaderStack(Deque<HeaderEntry> stack, HeaderBlock header) {
        while (!stack.isEmpty() && stack.peekLast().level() >= header.level()) {
            stack.removeLast();
        }
        stack.addLast(new HeaderEntry(header.level(), header.text()));
    }

    private String finalizeChunk(StringBuilder current, Deque<HeaderEntry> headerStack) {
        String body = current.toString().trim();
        current.setLength(0);
        if (body.isEmpty()) {
            return body;
        }
        String breadcrumb = breadcrumb(headerStack);
        if (breadcrumb.isEmpty()) {
            return body;
        }
        return breadcrumb + "\n\n" + body;
    }

    private String breadcrumb(Deque<HeaderEntry> headerStack) {
        return String.join("\n", headerStack.stream().map(HeaderEntry::text).toList());
    }

    private void appendToChunk(StringBuilder current, String text) {
        if (text.isBlank()) {
            return;
        }
        if (!current.isEmpty()) {
            current.append("\n\n");
        }
        current.append(text.trim());
    }

    private List<String> splitLargeText(String text, int maxSize) {
        if (text.length() <= maxSize) {
            return List.of(text);
        }

        List<String> parts = new ArrayList<>();
        String[] paragraphs = text.split("\n\n");
        StringBuilder buffer = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.length() > maxSize) {
                flushBuffer(parts, buffer);
                parts.addAll(splitBySentence(paragraph, maxSize));
                continue;
            }
            if (!buffer.isEmpty() && buffer.length() + paragraph.length() + 2 > maxSize) {
                flushBuffer(parts, buffer);
            }
            appendToChunk(buffer, paragraph);
        }
        flushBuffer(parts, buffer);
        return parts;
    }

    private List<String> splitBySentence(String text, int maxSize) {
        List<String> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int start = 0;
        Matcher matcher = SENTENCE_END.matcher(text);

        while (matcher.find()) {
            int end = matcher.end();
            String sentence = text.substring(start, end).trim();
            start = end;
            if (sentence.isEmpty()) {
                continue;
            }
            if (!buffer.isEmpty() && buffer.length() + sentence.length() + 1 > maxSize) {
                parts.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (sentence.length() > maxSize) {
                flushBuffer(parts, buffer);
                parts.add(sentence);
                continue;
            }
            if (!buffer.isEmpty()) {
                buffer.append(' ');
            }
            buffer.append(sentence);
        }

        if (start < text.length()) {
            String tail = text.substring(start).trim();
            if (!tail.isEmpty()) {
                if (!buffer.isEmpty() && buffer.length() + tail.length() + 1 > maxSize) {
                    parts.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                if (tail.length() > maxSize) {
                    flushBuffer(parts, buffer);
                    parts.add(tail);
                } else {
                    if (!buffer.isEmpty()) {
                        buffer.append(' ');
                    }
                    buffer.append(tail);
                }
            }
        }
        flushBuffer(parts, buffer);
        return parts;
    }

    private void flushBuffer(List<String> parts, StringBuilder buffer) {
        if (!buffer.isEmpty()) {
            parts.add(buffer.toString().trim());
            buffer.setLength(0);
        }
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.indexOf('|', 1) > 0;
    }

    private static int skipCodeFence(String[] lines, int start) {
        int i = start + 1;
        while (i < lines.length && !lines[i].trim().startsWith("```")) {
            i++;
        }
        return i < lines.length ? i + 1 : i;
    }

    private static String readCodeFence(String[] lines, int start) {
        StringBuilder code = new StringBuilder(lines[start].trim());
        int i = start + 1;
        while (i < lines.length && !lines[i].trim().startsWith("```")) {
            code.append('\n').append(lines[i]);
            i++;
        }
        if (i < lines.length) {
            code.append('\n').append(lines[i].trim());
        }
        return code.toString();
    }

    private sealed interface Block permits HeaderBlock, ParagraphBlock, TableBlock, CodeBlock {
        String text();
    }

    private record HeaderBlock(int level, String text) implements Block {
    }

    private record ParagraphBlock(String text) implements Block {
    }

    private record TableBlock(String text) implements Block {
    }

    private record CodeBlock(String text) implements Block {
    }

    private record HeaderEntry(int level, String text) {
    }
}
