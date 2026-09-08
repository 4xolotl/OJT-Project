package com.ojt.board.file;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByPostIdOrderByIdAsc(Long postId);

    @Query("select a.post.id from Attachment a where a.id = :id")
    Optional<Long> findPostIdById(@Param("id") Long id);
}
