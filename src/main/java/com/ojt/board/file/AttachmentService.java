package com.ojt.board.file;

import com.ojt.board.global.ResourceNotFoundException;
import com.ojt.board.global.EditConflictException;
import com.ojt.board.post.Post;
import com.ojt.board.post.PostRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final PostRepository postRepository;
    private final LocalFileStorage fileStorage;

    public List<AttachmentResponse> list(Long postId) {
        if (!postRepository.existsById(postId)) {
            throw new ResourceNotFoundException("게시글을 찾을 수 없습니다.");
        }
        return attachmentRepository.findByPostIdOrderByIdAsc(postId).stream()
                .map(AttachmentResponse::from).toList();
    }

    @Transactional
    public List<AttachmentResponse> upload(Long postId, Long actorId, List<MultipartFile> files) {
        Post post = requireLockedPost(postId);
        post.requireAuthor(actorId);
        return storeFiles(post, files);
    }

    private List<AttachmentResponse> storeFiles(Post post, List<MultipartFile> files) {
        validateBatch(files);
        List<Attachment> attachments = new ArrayList<>();
        for (MultipartFile file : files) {
            LocalFileStorage.StoredFile storedFile = fileStorage.store(file);
            removeOnRollback(storedFile.storedFilename());
            attachments.add(new Attachment(post, storedFile.originalFilename(),
                    storedFile.storedFilename(), storedFile.size()));
        }
        // Flush here so database failures reach the caller and trigger rollback cleanup.
        return attachmentRepository.saveAllAndFlush(attachments).stream()
                .map(AttachmentResponse::from).toList();
    }

    /** The caller must hold the post write lock and verify the author inside its edit transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void editForPost(Post post, List<Long> originalIds, List<Long> deletedIds, List<MultipartFile> files) {
        Set<Long> expected = new HashSet<>(originalIds);
        Set<Long> deleted = new HashSet<>(deletedIds);
        if (!expected.containsAll(deleted)) {
            throw new IllegalArgumentException("삭제할 파일은 처음 조회한 첨부파일 목록에 있어야 합니다.");
        }
        List<Attachment> current = attachmentRepository.findByPostIdOrderByIdAsc(post.getId());
        Set<Long> currentIds = new HashSet<>();
        current.forEach(attachment -> currentIds.add(attachment.getId()));
        if (!currentIds.equals(expected)) {
            throw new EditConflictException();
        }
        List<Attachment> removed = current.stream().filter(attachment -> deleted.contains(attachment.getId())).toList();
        if (!removed.isEmpty()) {
            attachmentRepository.deleteAll(removed);
            removeAfterCommit(removed.stream().map(Attachment::getStoredFilename).toList());
        }
        if (files != null && !files.isEmpty()) {
            storeFiles(post, files);
        }
    }

    public Download download(Long fileId) {
        Attachment attachment = requireAttachment(fileId);
        LocalFileStorage.OpenedFile openedFile = fileStorage.open(attachment.getStoredFilename());
        return new Download(attachment.getOriginalFilename(), openedFile.resource(), openedFile.size());
    }

    @Transactional
    public void delete(Long fileId, Long actorId) {
        // Fetch only the post ID first, then re-read the attachment after acquiring the post lock.
        // This shares the lock order used by uploads and post deletion.
        Long postId = attachmentRepository.findPostIdById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
        Post post = requireLockedPost(postId);
        post.requireAuthor(actorId);
        Attachment attachment = requireAttachment(fileId);
        attachmentRepository.delete(attachment);
        removeAfterCommit(List.of(attachment.getStoredFilename()));
    }

    /** The caller must hold the post write lock inside the post deletion transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllForPost(Post post) {
        List<Attachment> attachments = attachmentRepository.findByPostIdOrderByIdAsc(post.getId());
        List<String> storedFilenames = attachments.stream().map(Attachment::getStoredFilename).toList();
        attachmentRepository.deleteAll(attachments);
        removeAfterCommit(storedFilenames);
    }

    private Post requireLockedPost(Long postId) {
        return postRepository.findByIdForUpdate(postId)
                .orElseThrow(() -> new ResourceNotFoundException("게시글을 찾을 수 없습니다."));
    }

    private Attachment requireAttachment(Long fileId) {
        return attachmentRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("파일을 찾을 수 없습니다."));
    }

    private void validateBatch(List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.size() > LocalFileStorage.MAX_FILES_PER_REQUEST) {
            throw new IllegalArgumentException("파일은 한 번에 1~5개 업로드할 수 있습니다.");
        }
        long totalSize = 0;
        for (MultipartFile file : files) {
            fileStorage.validate(file);
            totalSize += file.getSize();
            if (totalSize > LocalFileStorage.MAX_REQUEST_SIZE) {
                throw new MaxUploadSizeExceededException(LocalFileStorage.MAX_REQUEST_SIZE);
            }
        }
    }

    private void removeOnRollback(String storedFilename) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    cleanupFile(storedFilename, "upload rolled back");
                } else if (status == STATUS_UNKNOWN) {
                    // An uncertain commit must not delete a file that might have committed metadata.
                    log.error("Upload transaction outcome unknown for stored file {}; verify metadata before cleanup",
                            storedFilename);
                }
            }
        });
    }

    private void removeAfterCommit(List<String> storedFilenames) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storedFilenames.forEach(filename -> cleanupFile(filename, "metadata deletion committed"));
            }
        });
    }

    private void cleanupFile(String storedFilename, String reason) {
        try {
            fileStorage.delete(storedFilename);
        } catch (RuntimeException exception) {
            // The database outcome is final here. Report the orphan explicitly for operational recovery.
            log.error("Physical file cleanup failed ({}) for {}; retry removal after resolving storage errors",
                    reason, storedFilename, exception);
        }
    }

    public record Download(String originalFilename, Resource resource, long size) {}
}
