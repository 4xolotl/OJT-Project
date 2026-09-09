package com.ojt.board.post;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PostSearchRepository {
    Page<Post> search(String pattern, Pageable pageable);
}
