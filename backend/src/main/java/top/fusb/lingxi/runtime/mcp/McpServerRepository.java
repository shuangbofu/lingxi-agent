package top.fusb.lingxi.runtime.mcp;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServerEntity, String> {

    List<McpServerEntity> findAllByOrderByNameAscCodeAsc();

    Page<McpServerEntity> findAllByOrderByNameAscCodeAsc(Pageable pageable);

    Optional<McpServerEntity> findByCode(String code);

}
