package com.ojt.board.post;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class PostSearchRepositoryImpl implements PostSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<Post> search(String pattern, Pageable pageable) {
        String condition = " where lower(p.title) like lower(:pattern) escape '!'"
                + " or lower(p.content) like lower(:pattern) escape '!'";
        Query postQuery = entityManager.createNativeQuery(
                        "select p.* from posts p" + condition + "\n order by p.created_at desc, p.id desc", Post.class)
                .setParameter("pattern", pattern)
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize());
        @SuppressWarnings("unchecked")
        List<Post> posts = postQuery.getResultList();
        Number count = (Number) entityManager.createNativeQuery("select count(*) from posts p" + condition + "\n")
                .setParameter("pattern", pattern)
                .getSingleResult();
        return new PageImpl<>(posts, pageable, count.longValue());
    }
}
