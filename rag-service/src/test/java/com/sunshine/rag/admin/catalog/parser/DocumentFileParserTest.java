package com.sunshine.rag.admin.catalog.parser;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.DocumentSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentFileParserTest {

    @Mock
    private PdfDocumentParser pdfDocumentParser;

    @Mock
    private DocxDocumentParser docxDocumentParser;

    @InjectMocks
    private DocumentFileParser documentFileParser;

    @Test
    void parsePdf_delegatesToPdfDocumentParser() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1});
        when(pdfDocumentParser.parse(file)).thenReturn("# OCR 结果");
        assertEquals("# OCR 结果", documentFileParser.parse(DocumentSourceType.PDF, file));
        verify(pdfDocumentParser).parse(file);
    }

    @Test
    void parseMarkdown_readsUtf8() {
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown", "# hi".getBytes());
        assertEquals("# hi", documentFileParser.parse(DocumentSourceType.MARKDOWN, file));
    }

    @Test
    void parseDocx_delegatesToDocxDocumentParser() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.docx", "application/octet-stream", new byte[]{1});
        when(docxDocumentParser.parse(new byte[]{1})).thenReturn("word text");
        assertEquals("word text", documentFileParser.parse(DocumentSourceType.DOCX, file));
    }

    @Test
    void isAsyncSourceType_pdfAndDocx() {
        assertTrue(documentFileParser.isAsyncSourceType(DocumentSourceType.PDF));
        assertTrue(documentFileParser.isAsyncSourceType(DocumentSourceType.DOCX));
    }
}
