package top.fusb.lingxi.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.demo.domain.MarkdownDocumentEntity;

import java.util.List;

public interface MarkdownDocumentRepository extends JpaRepository<MarkdownDocumentEntity, Long> {

    List<MarkdownDocumentEntity> findByProjectCodeIgnoreCaseOrderByPathAsc(String projectCode);

    List<MarkdownDocumentEntity> findAllByOrderByUpdatedAtDesc();

    void deleteByProjectCodeIgnoreCase(String projectCode);
}
