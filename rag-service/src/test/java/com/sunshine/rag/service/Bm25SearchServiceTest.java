package com.sunshine.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagElasticsearchProperties;
import com.sunshine.rag.model.RetrievalCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25SearchServiceTest {

    private Bm25SearchService service;

    @BeforeEach
    void setUp() {
        RagElasticsearchProperties props = new RagElasticsearchProperties();
        props.setEnabled(false);
        service = new Bm25SearchService(props, new ObjectMapper());
    }

    @Test
    void parseHitsExtractsCandidates() throws Exception {
        String json = """
                {
                  "hits": {
                    "hits": [
                      {
                        "_score": 8.2,
                        "_source": {
                          "chunk_id": "报销#0",
                          "doc_name": "公司报销管理制度",
                          "content": "餐费发票可以报销"
                        }
                      }
                    ]
                  }
                }
                """;
        List<RetrievalCandidate> hits = service.parseHits(json);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).docName()).isEqualTo("公司报销管理制度");
        assertThat(hits.get(0).source()).isEqualTo(RetrievalCandidate.SOURCE_BM25);
    }
}
