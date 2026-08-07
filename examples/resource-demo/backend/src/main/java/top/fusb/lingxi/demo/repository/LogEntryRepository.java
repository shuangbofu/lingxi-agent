package top.fusb.lingxi.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.demo.domain.LogEntryEntity;

import java.util.List;

public interface LogEntryRepository extends JpaRepository<LogEntryEntity, Long> {

    List<LogEntryEntity> findTop500ByOrderByOccurredAtDesc();

    List<LogEntryEntity> findTop500ByProjectIdOrderByOccurredAtDesc(Long projectId);

    List<LogEntryEntity> findTop500ByProjectIdAndEnvironmentCodeOrderByOccurredAtDesc(Long projectId, String environmentCode);

    List<LogEntryEntity> findByProjectIdOrderByOccurredAtAsc(Long projectId);
}
