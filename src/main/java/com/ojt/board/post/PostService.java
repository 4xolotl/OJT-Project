package com.ojt.board.post;

import com.ojt.board.file.AttachmentService;
import com.ojt.board.global.EditConflictException;
import com.ojt.board.global.PageResponse;
import com.ojt.board.global.ResourceNotFoundException;
import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final AttachmentService attachmentService;

    @PersistenceContext
    private EntityManager entityManager;

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
        return PostResponse.from(savePost(actorId, request));
    }

    @Transactional
    public PostResponse createWithFiles(Long actorId, PostRequest request, List<MultipartFile> files) {
        Post post = savePost(actorId, request);
        if (files != null && !files.isEmpty()) {
            // Upload joins this transaction; its rollback callbacks clean up already stored files.
            attachmentService.upload(post.getId(), actorId, files);
        }
        return PostResponse.from(post);
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
    public PostResponse updateWithFiles(Long id, Long actorId, PostEditRequest request, List<MultipartFile> files) {
        Post post = findForUpdate(id);
        post.requireAuthor(actorId);
        if (!new HashSet<>(request.attachmentIds()).containsAll(request.deletedFileIds())) {
            throw new IllegalArgumentException("삭제할 파일은 처음 조회한 첨부파일 목록에 있어야 합니다.");
        }
        if (!post.getUpdatedAt().equals(request.updatedAt())) {
            throw new EditConflictException();
        }
        attachmentService.editForPost(post, request.attachmentIds(), request.deletedFileIds(), files);
        if (!post.getTitle().equals(request.title()) || !post.getContent().equals(request.content())) {
            post.update(request.title(), request.content());
        }
        postRepository.flush();
        // Return the stored DATETIME(6) precision so the response can be used as the next edit snapshot.
        entityManager.refresh(post);
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

    private Post savePost(Long actorId, PostRequest request) {
        User author = userRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다."));
        return postRepository.save(new Post(author, request.title(), request.content()));
    }

    private Post findForUpdate(Long id) {
        return postRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("게시글을 찾을 수 없습니다."));
    }
}
