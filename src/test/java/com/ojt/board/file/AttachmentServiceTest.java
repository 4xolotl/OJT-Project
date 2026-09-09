package com.ojt.board.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ojt.board.post.Post;
import com.ojt.board.post.PostRepository;
import java.io.IOException;
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

    @TempDir
    Path temporaryDirectory;

    private AttachmentRepository attachmentRepository;
    private LocalFileStorage storage;
    private AttachmentService service;
    private Post post;

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(AttachmentRepository.class);
        PostRepository postRepository = mock(PostRepository.class);
        storage = new LocalFileStorage(temporaryDirectory.toString());
        service = new AttachmentService(attachmentRepository, postRepository, storage);
        post = mock(Post.class);
        when(post.getId()).thenReturn(1L);
        when(postRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(post));
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
        assertEquals(1, service.upload(1L, 8L, List.of(validFile())).size());
        assertEquals(1, storedFileCount());
        verify(attachmentRepository).saveAllAndFlush(any());
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
        when(attachmentRepository.findPostIdById(5L)).thenReturn(Optional.of(1L));
        when(attachmentRepository.findById(5L)).thenReturn(Optional.of(attachment));

        service.delete(5L, 7L);
        verify(attachmentRepository).delete(attachment);
        assertTrue(Files.exists(temporaryDirectory.resolve(attachment.getStoredFilename())));

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        assertFalse(Files.exists(temporaryDirectory.resolve(attachment.getStoredFilename())));
    }

    @Test
    void keepsAllPhysicalFilesIfPostDeletionRollsBack() throws Exception {
        Attachment attachment = storedAttachment();
        when(attachmentRepository.findByPostIdOrderByIdAsc(1L)).thenReturn(List.of(attachment));
        service.deleteAllForPost(post);

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(Files.exists(temporaryDirectory.resolve(attachment.getStoredFilename())));
        verify(attachmentRepository).deleteAll(List.of(attachment));
    }

    @Test
    void removesAllPhysicalFilesWhenPostDeletionCommits() throws Exception {
        Attachment first = storedAttachment();
        Attachment second = storedAttachment();
        when(attachmentRepository.findByPostIdOrderByIdAsc(1L)).thenReturn(List.of(first, second));
        service.deleteAllForPost(post);
        assertEquals(2, storedFileCount());

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        assertEquals(0, storedFileCount());
    }

    private Attachment storedAttachment() {
        LocalFileStorage.StoredFile stored = storage.store(validFile());
        return new Attachment(post, stored.originalFilename(), stored.storedFilename(), stored.size());
    }

    private MockMultipartFile validFile() {
        return new MockMultipartFile("files", "report.txt", "text/plain", new byte[]{1, 2, 3});
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
