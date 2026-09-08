package com.ojt.board.post;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Override
    @EntityGraph(attributePaths = "author")
    Page<Post> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "author")
    Optional<Post> findById(Long id);

    @EntityGraph(attributePaths = "author")
    @Query("select p from Post p where lower(p.title) like lower(:pattern) escape '!' "
            + "or lower(p.content) like lower(:pattern) escape '!'")
    Page<Post> search(@Param("pattern") String pattern, Pageable pageable);

    // Every child mutation takes this same lock before touching a post's contents.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") Long id);
}
