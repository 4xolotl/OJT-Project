package com.ojt.board.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ojt.board.authorization.ObjectAuthorizationDeniedException;
import com.ojt.board.authorization.ObjectAuthorizationGuard;
import com.ojt.board.authorization.ObjectAuthorizationOperation;
import com.ojt.board.post.Post;
import com.ojt.board.post.PostRepository;
import com.ojt.board.user.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AttachmentServiceTest {

    private static final long POST_ID = 1L;
    private static final long FILE_ID = 5L;
    private static final long OWNER_ID = 7L;
    private static final long OTHER_ID = 8L;

    @TempDir
    Path temporaryDirectory;

    private AttachmentRepository attachmentRepository;
    private PostRepository postRepository;
    private LocalFileStorage storage;
    private AttachmentService service;
    private Post post;

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(AttachmentRepository.class);
        postRepository = mock(PostRepository.class);
        storage = new LocalFileStorage(temporaryDirectory.toString());
        service = new AttachmentService(attachmentRepository, postRepository, storage,
                new ObjectAuthorizationGuard());
        post = mock(Post.class);
        User author = mock(User.class);
        when(author.getId()).thenReturn(OWNER_ID);
        when(post.getId()).thenReturn(POST_ID);
        when(post.getAuthor()).thenReturn(author);
        when(postRepository.findAuthorIdById(POST_ID)).thenReturn(Optional.of(OWNER_ID));
        when(postRepository.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void validatesAllFilesBeforeWritingAnyFile() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.upload(1L, 7L, List.of(validFile(),
                new MockMultipartFile("files", "empty.txt", null, new byte[0]))));

        assertEquals(0, storedFileCount());
        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void storesFilesForTheRequestedPost() throws Exception {
        when(attachmentRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        List<AttachmentResponse> response = service.upload(POST_ID, OWNER_ID, List.of(validFile()));
        assertEquals(1, response.size());
        assertEquals("text/plain", response.getFirst().contentType());
        assertEquals(1, storedFileCount());
        verify(attachmentRepository).saveAllAndFlush(any());
    }

    @Test
    void storesMarkupOnlyAsCanonicalPlainText() throws Exception {
        when(attachmentRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile disguisedHtml = new MockMultipartFile("files", "notes.txt", "text/plain",
                "<!doctype html><script>alert(document.domain)</script>".getBytes(StandardCharsets.UTF_8));

        List<AttachmentResponse> response = service.upload(1L, 7L, List.of(disguisedHtml));

        assertEquals("text/plain", response.getFirst().contentType());
        assertEquals(1, storedFileCount());
        verify(attachmentRepository).saveAllAndFlush(any());
    }

    @Test
    void downloadUsesTheMimeTypeVerifiedAtUpload() throws Exception {
        Attachment attachment = storedAttachment();
        when(attachmentRepository.findById(FILE_ID)).thenReturn(Optional.of(attachment));

        AttachmentService.Download download = service.download(FILE_ID);

        assertEquals("text/plain", download.contentType());
        assertEquals(attachment.getSize(), download.size());
        try (var ignored = download.resource().getInputStream()) {
            assertTrue(ignored.read() >= 0);
        }
    }

    @Test
    void removesAllNewFilesAfterDatabaseRollback() throws Exception {
        when(attachmentRepository.saveAllAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("simulated database failure"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.upload(1L, 7L, List.of(validFile(), validFile())));
        assertEquals(2, storedFileCount());
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals(0, storedFileCount());
    }

    @Test
    void keepsUploadedFilesAfterSuccessfulCommit() throws Exception {
        when(attachmentRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(1, service.upload(1L, 7L, List.of(validFile())).size());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        assertEquals(1, storedFileCount());
    }

    @Test
    void removesPhysicalFileOnlyAfterMetadataDeletionCommits() throws Exception {
        Attachment attachment = storedAttachment();
        when(attachmentRepository.findPostIdById(FILE_ID)).thenReturn(Optional.of(POST_ID));
        when(attachmentRepository.findByIdAndPostIdForUpdate(FILE_ID, POST_ID))
                .thenReturn(Optional.of(attachment));

        service.delete(FILE_ID, OWNER_ID);
        verify(attachmentRepository).delete(attachment);
        assertTrue(Files.exists(temporaryDirectory.resolve(attachment.getStoredFilename())));

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        assertFalse(Files.exists(temporaryDirectory.resolve(attachment.getStoredFilename())));
    }

    @Test
    void keepsAllPhysicalFilesIfPostDeletionRollsBack() throws Exception {
        Attachment attachment = storedAttachment();
        when(attachmentRepository.findByPostIdOrderByIdAscForUpdate(POST_ID)).thenReturn(List.of(attachment));
        service.deleteAllForPost(post);

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(Files.exists(temporaryDirectory.resolve(attachment.getStoredFilename())));
        verify(attachmentRepository).deleteAll(List.of(attachment));
    }

    @Test
    void removesAllPhysicalFilesWhenPostDeletionCommits() throws Exception {
        Attachment first = storedAttachment();
        Attachment second = storedAttachment();
        when(attachmentRepository.findByPostIdOrderByIdAscForUpdate(POST_ID)).thenReturn(List.of(first, second));
        service.deleteAllForPost(post);
        assertEquals(2, storedFileCount());

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        assertEquals(0, storedFileCount());
    }

    @Test
    void nonOwnerCannotUploadBeforePostLock() throws Exception {
        ObjectAuthorizationDeniedException exception = assertThrows(ObjectAuthorizationDeniedException.class,
                () -> service.upload(POST_ID, OTHER_ID, List.of(validFile())));

        assertDenied(exception, POST_ID, ObjectAuthorizationOperation.ATTACHMENT_UPLOAD);
        verify(postRepository).findAuthorIdById(POST_ID);
        verify(postRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(attachmentRepository);
        assertEquals(0, storedFileCount());
    }

    @Test
    void nonOwnerCannotDeleteBeforePostLock() {
        when(attachmentRepository.findPostIdById(FILE_ID)).thenReturn(Optional.of(POST_ID));

        ObjectAuthorizationDeniedException exception = assertThrows(ObjectAuthorizationDeniedException.class,
                () -> service.delete(FILE_ID, OTHER_ID));

        assertDenied(exception, FILE_ID, ObjectAuthorizationOperation.ATTACHMENT_DELETE);
        verify(attachmentRepository).findPostIdById(FILE_ID);
        verify(postRepository).findAuthorIdById(POST_ID);
        verify(postRepository, never()).findByIdForUpdate(anyLong());
        verify(attachmentRepository, never()).findByIdAndPostIdForUpdate(anyLong(), anyLong());
        verify(attachmentRepository, never()).delete(any(Attachment.class));
    }

    private Attachment storedAttachment() {
        LocalFileStorage.StoredFile stored = storage.store(validFile());
        return new Attachment(post, stored.originalFilename(), stored.storedFilename(), stored.size(),
                stored.contentType());
    }

    private MockMultipartFile validFile() {
        return new MockMultipartFile("files", "report.txt", "text/plain",
                "report body".getBytes(StandardCharsets.UTF_8));
    }

    private void assertDenied(ObjectAuthorizationDeniedException exception, long resourceId,
                              ObjectAuthorizationOperation operation) {
        assertEquals("게시글 작성자만 수정하거나 삭제할 수 있습니다.", exception.getMessage());
        assertEquals(OTHER_ID, exception.actorId());
        assertEquals(resourceId, exception.resourceId());
        assertEquals(operation, exception.operation());
    }

    private long storedFileCount() throws IOException {
        try (var files = Files.list(temporaryDirectory)) {
            return files.count();
        }
    }

    private void completeTransaction(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
    }
}
