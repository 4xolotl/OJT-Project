package com.ojt.board.comment;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ojt.board.global.PageResponse;
import com.ojt.board.global.ResourceNotFoundException;
import com.ojt.board.post.Post;
import com.ojt.board.post.PostRepository;
import com.ojt.board.user.User;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;

    public PageResponse<CommentResponse> list(Long postId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page는 0 이상, size는 1~100이어야 합니다.");
        }
        if (!postRepository.existsById(postId)) {
            throw new ResourceNotFoundException("게시글을 찾을 수 없습니다.");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt", "id").ascending());
        return PageResponse.from(commentRepository.findAllByPostId(postId, pageable)
                .map(CommentResponse::from));
    }

    @Transactional
    public CommentResponse create(Long postId, User author, CommentRequest request) {
        Post post = lockPost(postId);
        Comment comment = new Comment(post, author, request.content());
        return CommentResponse.from(commentRepository.saveAndFlush(comment));
    }

    @Transactional
    public CommentResponse update(Long postId, Long commentId, User user, CommentRequest request) {
        lockPost(postId);
        Comment comment = findComment(postId, commentId);
        requireAuthor(comment, user);
        comment.updateContent(request.content());
        commentRepository.flush();
        return CommentResponse.from(comment);
    }

    @Transactional
    public void delete(Long postId, Long commentId, User user) {
        lockPost(postId);
        Comment comment = findComment(postId, commentId);
        requireAuthor(comment, user);
        commentRepository.delete(comment);
    }

    private Post lockPost(Long postId) {
        return postRepository.findByIdForUpdate(postId)
                .orElseThrow(() -> new ResourceNotFoundException("게시글을 찾을 수 없습니다."));
    }

    private Comment findComment(Long postId, Long commentId) {
        return commentRepository.findByIdAndPostId(commentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException("댓글을 찾을 수 없습니다."));
    }

    private void requireAuthor(Comment comment, User user) {
        if (!comment.getAuthor().getId().equals(user.getId())) {
            throw new AccessDeniedException("댓글 작성자만 수정하거나 삭제할 수 있습니다.");
        }
    }
}
