package com.ojt.board.comment;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @EntityGraph(attributePaths = "author")
    Page<Comment> findAllByPostId(Long postId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Comment c where c.id = :id and c.post.id = :postId")
    Optional<Comment> findByIdAndPostIdForUpdate(@Param("id") Long id, @Param("postId") Long postId);

    @Query("select c.author.id from Comment c where c.id = :id and c.post.id = :postId")
    Optional<Long> findAuthorIdByIdAndPostId(@Param("id") Long id, @Param("postId") Long postId);
}
