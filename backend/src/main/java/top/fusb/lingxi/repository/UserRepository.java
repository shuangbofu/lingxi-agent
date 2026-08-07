package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    Page<UserEntity> findAllByOrderByCreatedAtAsc(Pageable pageable);

    boolean existsByUsername(String username);
}
