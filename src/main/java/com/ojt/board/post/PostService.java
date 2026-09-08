package com.ojt.board.post;

import com.ojt.board.file.AttachmentService;
import com.ojt.board.global.PageResponse;
import com.ojt.board.global.ResourceNotFoundException;
import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final AttachmentService attachmentService;

    public PageResponse<PostResponse> list(int page, int size, String keyword) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Post> posts;
        if (keyword == null || keyword.isBlank()) {
            posts = postRepository.findAll(pageable);
        } else {
            String escaped = keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            posts = postRepository.search("%" + escaped + "%", pageable);
        }
        return PageResponse.from(posts.map(PostResponse::from));
    }

    public PostResponse get(Long id) {
        return PostResponse.from(postRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("게시글을 찾을 수 없습니다.")));
    }

    @Transactional
    public PostResponse create(Long actorId, PostRequest request) {
        User author = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다."));
        return PostResponse.from(postRepository.save(new Post(author, request.title(), request.content())));
    }

    @Transactional
    public PostResponse update(Long id, Long actorId, PostRequest request) {
        Post post = findForUpdate(id);
        post.requireAuthor(actorId);
        post.update(request.title(), request.content());
        postRepository.flush();
        return PostResponse.from(post);
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        Post post = findForUpdate(id);
        post.requireAuthor(actorId);
        attachmentService.deleteAllForPost(post);
        // Comment foreign keys use ON DELETE CASCADE. Physical files are removed after commit.
        postRepository.delete(post);
        postRepository.flush();
    }

    private Post findForUpdate(Long id) {
        return postRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("게시글을 찾을 수 없습니다."));
    }
}
