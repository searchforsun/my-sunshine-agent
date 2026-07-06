package com.sunshine.rag.admin.catalog.parser;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** DOCX → Markdown（段落 + 表格，保持文档顺序） */
@Slf4j
@Component
public class DocxDocumentParser {

    public String parse(byte[] bytes) {
        return parse(bytes, null);
    }

    public String parse(byte[] bytes, ParseProgressListener progress) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<IBodyElement> elements = doc.getBodyElements();
            int total = Math.max(1, elements.size());
            List<String> blocks = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                IBodyElement element = elements.get(i);
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (StringUtils.hasText(text)) {
                        blocks.add(text.strip());
                    }
                } else if (element instanceof XWPFTable table) {
                    String markdown = tableToMarkdown(table);
                    if (StringUtils.hasText(markdown)) {
                        blocks.add(markdown);
                    }
                }
                if (progress != null && (i == elements.size() - 1 || i % 3 == 0)) {
                    progress.onProgress(i + 1, total, (i + 1) * 100.0 / total);
                }
            }
            if (blocks.isEmpty()) {
                throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
            }
            if (progress != null) {
                progress.onProgress(total, total, 100.0);
            }
            return String.join("\n\n", blocks);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.warn("[RAG] DOCX 解析失败: {}", e.getMessage());
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
    }

    static String tableToMarkdown(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText();
                cells.add(text != null ? text.strip().replace("|", "\\|").replace('\n', ' ') : "");
            }
            if (cells.stream().anyMatch(s -> !s.isBlank())) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        List<String> header = padRow(rows.getFirst(), cols);
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|");
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = padRow(rows.get(r), cols);
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString().strip();
    }

    private static List<String> padRow(List<String> row, int cols) {
        List<String> padded = new ArrayList<>(row);
        while (padded.size() < cols) {
            padded.add("");
        }
        return padded.subList(0, cols);
    }
}
