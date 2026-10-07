package com.ojt.board.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ojt.board.authorization.ObjectAuthorizationDeniedException;
import com.ojt.board.authorization.ObjectAuthorizationGuard;
import com.ojt.board.authorization.ObjectAuthorizationOperation;
import com.ojt.board.post.PostRepository;
import com.ojt.board.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommentServiceTest {

    private static final long POST_ID = 1L;
    private static final long COMMENT_ID = 2L;
    private static final long OWNER_ID = 7L;
    private static final long OTHER_ID = 8L;

    private CommentRepository commentRepository;
    private PostRepository postRepository;
    private CommentService service;

    @BeforeEach
    void setUp() {
        commentRepository = mock(CommentRepository.class);
        postRepository = mock(PostRepository.class);
        service = new CommentService(commentRepository, postRepository, mock(UserRepository.class),
                new ObjectAuthorizationGuard());
        when(commentRepository.findAuthorIdByIdAndPostId(COMMENT_ID, POST_ID))
                .thenReturn(Optional.of(OWNER_ID));
    }

    @Test
    void nonAuthorCannotUpdateBeforePostLock() {
        ObjectAuthorizationDeniedException exception = assertThrows(ObjectAuthorizationDeniedException.class,
                () -> service.update(POST_ID, COMMENT_ID, OTHER_ID, new CommentRequest("Changed")));

        assertDenied(exception, ObjectAuthorizationOperation.COMMENT_UPDATE);
        verify(commentRepository).findAuthorIdByIdAndPostId(COMMENT_ID, POST_ID);
        verify(postRepository, never()).findByIdForUpdate(anyLong());
        verify(commentRepository, never()).findByIdAndPostIdForUpdate(anyLong(), anyLong());
        verify(commentRepository, never()).flush();
    }

    @Test
    void nonAuthorCannotDeleteBeforePostLock() {
        ObjectAuthorizationDeniedException exception = assertThrows(ObjectAuthorizationDeniedException.class,
                () -> service.delete(POST_ID, COMMENT_ID, OTHER_ID));

        assertDenied(exception, ObjectAuthorizationOperation.COMMENT_DELETE);
        verify(commentRepository).findAuthorIdByIdAndPostId(COMMENT_ID, POST_ID);
        verify(postRepository, never()).findByIdForUpdate(anyLong());
        verify(commentRepository, never()).findByIdAndPostIdForUpdate(anyLong(), anyLong());
        verify(commentRepository, never()).delete(any(Comment.class));
    }

    private void assertDenied(ObjectAuthorizationDeniedException exception,
                              ObjectAuthorizationOperation operation) {
        assertEquals("댓글 작성자만 수정하거나 삭제할 수 있습니다.", exception.getMessage());
        assertEquals(OTHER_ID, exception.actorId());
        assertEquals(COMMENT_ID, exception.resourceId());
        assertEquals(operation, exception.operation());
    }
}
