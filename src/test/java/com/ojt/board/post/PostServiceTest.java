package com.ojt.board.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ojt.board.authorization.ObjectAuthorizationDeniedException;
import com.ojt.board.authorization.ObjectAuthorizationGuard;
import com.ojt.board.authorization.ObjectAuthorizationOperation;
import com.ojt.board.file.AttachmentService;
import com.ojt.board.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostServiceTest {

    private static final long POST_ID = 1L;
    private static final long OWNER_ID = 7L;
    private static final long OTHER_ID = 8L;

    private PostRepository postRepository;
    private AttachmentService attachmentService;
    private PostService service;

    @BeforeEach
    void setUp() {
        postRepository = mock(PostRepository.class);
        attachmentService = mock(AttachmentService.class);
        service = new PostService(postRepository, mock(UserRepository.class), attachmentService,
                new ObjectAuthorizationGuard());
        when(postRepository.findAuthorIdById(POST_ID)).thenReturn(Optional.of(OWNER_ID));
    }

    @Test
    void nonAuthorCannotUpdateBeforePostLock() {
        ObjectAuthorizationDeniedException exception = assertThrows(ObjectAuthorizationDeniedException.class,
                () -> service.update(POST_ID, OTHER_ID, new PostRequest("Changed", "Changed content")));

        assertDenied(exception, ObjectAuthorizationOperation.POST_UPDATE);
        verify(postRepository).findAuthorIdById(POST_ID);
        verify(postRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void nonAuthorCannotEditWithFilesBeforePostLock() {
        PostEditRequest request = new PostEditRequest("Changed", "Changed content", Instant.EPOCH,
                List.of(), List.of());

        ObjectAuthorizationDeniedException exception = assertThrows(ObjectAuthorizationDeniedException.class,
                () -> service.updateWithFiles(POST_ID, OTHER_ID, request, List.of()));

        assertDenied(exception, ObjectAuthorizationOperation.POST_UPDATE);
        verify(postRepository).findAuthorIdById(POST_ID);
        verify(postRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(attachmentService);
    }

    @Test
    void nonAuthorCannotDeleteBeforePostLock() {
        ObjectAuthorizationDeniedException exception = assertThrows(ObjectAuthorizationDeniedException.class,
                () -> service.delete(POST_ID, OTHER_ID));

        assertDenied(exception, ObjectAuthorizationOperation.POST_DELETE);
        verify(postRepository).findAuthorIdById(POST_ID);
        verify(postRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(attachmentService);
    }

    private void assertDenied(ObjectAuthorizationDeniedException exception,
                              ObjectAuthorizationOperation operation) {
        assertEquals("게시글 작성자만 수정하거나 삭제할 수 있습니다.", exception.getMessage());
        assertEquals(OTHER_ID, exception.actorId());
        assertEquals(POST_ID, exception.resourceId());
        assertEquals(operation, exception.operation());
    }
}
