package com.sunshine.rag.admin.catalog.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    void extractsTableAsMarkdown() throws Exception {
        byte[] bytes = buildDocxWithTable();
        String markdown = parser.parse(bytes);
        assertThat(markdown).contains("| 测试 11 | 测试 22 |");
        assertThat(markdown).contains("| --- | --- |");
        assertThat(markdown).contains("| 测试 33 | 测试 44 |");
    }

    private static byte[] buildDocxWithTable() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("标题行");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("测试 11");
            table.getRow(0).getCell(1).setText("测试 22");
            table.getRow(1).getCell(0).setText("测试 33");
            table.getRow(1).getCell(1).setText("测试 44");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
