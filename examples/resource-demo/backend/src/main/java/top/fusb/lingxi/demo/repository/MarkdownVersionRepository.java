package top.fusb.lingxi.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.demo.domain.MarkdownVersionEntity;

import java.util.List;

public interface MarkdownVersionRepository extends JpaRepository<MarkdownVersionEntity, Long> {

    List<MarkdownVersionEntity> findByDocumentIdOrderByRevisionDesc(Long documentId);
}
