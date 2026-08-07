package top.fusb.lingxi.demo.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Service;
import top.fusb.lingxi.demo.domain.MarkdownDocumentEntity;
import top.fusb.lingxi.demo.repository.MarkdownDocumentRepository;
import top.fusb.lingxi.demo.web.BusinessException;
import top.fusb.lingxi.demo.web.ErrorCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSearchIndex {

    private static final String FIELD_ID = "documentId";
    private static final String FIELD_PROJECT = "projectCode";
    private static final List<String> TEXT_FIELDS = List.of("title", "path", "content");

    private final MarkdownDocumentRepository documentRepository;
    private final Directory directory = new ByteBuffersDirectory();
    private final Analyzer analyzer = new SmartChineseAnalyzer();

    @PostConstruct
    public synchronized void rebuild() {
        try (IndexWriter writer = new IndexWriter(directory,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            List<MarkdownDocumentEntity> documents = documentRepository.findAll();
            for (MarkdownDocumentEntity document : documents) {
                writer.addDocument(toIndexDocument(document));
            }
            writer.commit();
            log.info("Rebuilt demo Markdown Lucene index documents={}", documents.size());
        } catch (IOException exception) {
            throw searchFailure(exception);
        }
    }

    public synchronized void update(MarkdownDocumentEntity document) {
        try (IndexWriter writer = new IndexWriter(directory,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
            writer.updateDocument(new Term(FIELD_ID, String.valueOf(document.getId())), toIndexDocument(document));
            writer.commit();
        } catch (IOException exception) {
            throw searchFailure(exception);
        }
    }

    public synchronized void delete(Long documentId) {
        try (IndexWriter writer = new IndexWriter(directory,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
            writer.deleteDocuments(new Term(FIELD_ID, String.valueOf(documentId)));
            writer.commit();
        } catch (IOException exception) {
            throw searchFailure(exception);
        }
    }

    public synchronized void deleteProject(String projectCode) {
        try (IndexWriter writer = new IndexWriter(directory,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
            writer.deleteDocuments(new Term(FIELD_PROJECT, projectCode.toLowerCase(Locale.ROOT)));
            writer.commit();
        } catch (IOException exception) {
            throw searchFailure(exception);
        }
    }

    public synchronized List<Long> search(String projectCode, String text, String mode, int limit) {
        List<String> terms = analyze(text);
        if (terms.isEmpty()) {
            return List.of();
        }
        BooleanQuery query = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(FIELD_PROJECT, projectCode.toLowerCase(Locale.ROOT))), BooleanClause.Occur.MUST)
                .add(textQuery(terms, mode), BooleanClause.Occur.MUST)
                .build();
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            ScoreDoc[] hits = searcher.search(query, Math.max(1, limit)).scoreDocs;
            List<Long> result = new ArrayList<>();
            for (ScoreDoc hit : hits) {
                result.add(Long.valueOf(searcher.storedFields().document(hit.doc).get(FIELD_ID)));
            }
            return result;
        } catch (IOException exception) {
            throw searchFailure(exception);
        }
    }

    @PreDestroy
    public void close() throws IOException {
        analyzer.close();
        directory.close();
    }

    private Query textQuery(List<String> terms, String mode) {
        String normalizedMode = mode == null ? "ALL_TERMS" : mode.toUpperCase(Locale.ROOT);
        if ("LITERAL".equals(normalizedMode)) {
            BooleanQuery.Builder fields = new BooleanQuery.Builder();
            for (String field : TEXT_FIELDS) {
                PhraseQuery.Builder phrase = new PhraseQuery.Builder();
                for (int index = 0; index < terms.size(); index++) {
                    phrase.add(new Term(field, terms.get(index)), index);
                }
                fields.add(phrase.build(), BooleanClause.Occur.SHOULD);
            }
            return fields.build();
        }

        BooleanClause.Occur termOccur = "ANY_TERMS".equals(normalizedMode)
                ? BooleanClause.Occur.SHOULD : BooleanClause.Occur.MUST;
        BooleanQuery.Builder termsQuery = new BooleanQuery.Builder();
        for (String term : terms) {
            BooleanQuery.Builder fields = new BooleanQuery.Builder();
            for (String field : TEXT_FIELDS) {
                fields.add(new TermQuery(new Term(field, term)), BooleanClause.Occur.SHOULD);
            }
            termsQuery.add(fields.build(), termOccur);
        }
        return termsQuery.build();
    }

    private List<String> analyze(String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("query", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
            return terms;
        } catch (IOException exception) {
            throw searchFailure(exception);
        }
    }

    private Document toIndexDocument(MarkdownDocumentEntity source) {
        Document document = new Document();
        document.add(new StringField(FIELD_ID, String.valueOf(source.getId()), Field.Store.YES));
        document.add(new StringField(FIELD_PROJECT, source.getProjectCode().toLowerCase(Locale.ROOT), Field.Store.NO));
        document.add(new TextField("title", source.getTitle(), Field.Store.NO));
        document.add(new TextField("path", source.getPath(), Field.Store.NO));
        document.add(new TextField("content", source.getContent(), Field.Store.NO));
        return document;
    }

    private BusinessException searchFailure(Exception exception) {
        log.error("Demo Markdown Lucene index operation failed", exception);
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "Markdown 搜索索引处理失败");
    }
}
