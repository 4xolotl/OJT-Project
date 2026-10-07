package com.ojt.board.file;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByPostIdOrderByIdAsc(Long postId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attachment a where a.post.id = :postId order by a.id asc")
    List<Attachment> findByPostIdOrderByIdAscForUpdate(@Param("postId") Long postId);

    @Query("select a.post.id from Attachment a where a.id = :id")
    Optional<Long> findPostIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attachment a where a.id = :id and a.post.id = :postId")
    Optional<Attachment> findByIdAndPostIdForUpdate(@Param("id") Long id, @Param("postId") Long postId);
}
