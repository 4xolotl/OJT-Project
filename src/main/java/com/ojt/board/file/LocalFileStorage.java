package com.ojt.board.file;

import com.ojt.board.global.ResourceNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@Component
@Slf4j
public class LocalFileStorage {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final long MAX_REQUEST_SIZE = 50L * 1024 * 1024;
    public static final int MAX_FILES_PER_REQUEST = 5;
    private static final Pattern STORED_FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path location;

    public LocalFileStorage(@Value("${app.storage.location:./uploads}") String location) {
        this.location = Path.of(location).toAbsolutePath().normalize();
    }

    public String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일은 업로드할 수 없습니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new MaxUploadSizeExceededException(MAX_FILE_SIZE);
        }
        return safeOriginalFilename(file.getOriginalFilename());
    }

    static String safeOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("파일 이름이 올바르지 않습니다.");
        }
        String normalized = Normalizer.normalize(originalFilename.replace('\\', '/'), Normalizer.Form.NFC);
        if (Arrays.stream(normalized.split("/")).anyMatch(segment -> segment.equals(".."))) {
            throw new IllegalArgumentException("파일 이름에 상위 경로를 사용할 수 없습니다.");
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255) {
            throw new IllegalArgumentException("파일 이름은 경로를 제외하고 1~255자여야 합니다.");
        }
        return name;
    }

    public StoredFile store(MultipartFile file) {
        String originalFilename = validate(file);
        String storedFilename = UUID.randomUUID().toString();
        Path target = null;
        boolean created = false;
        try {
            Files.createDirectories(location);
            target = resolve(storedFilename);
            // CREATE_NEW prevents overwrites, and NOFOLLOW_LINKS rejects a substituted symbolic link.
            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                try (InputStream input = file.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    long size = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        size += count;
                        if (size > MAX_FILE_SIZE) {
                            throw new MaxUploadSizeExceededException(MAX_FILE_SIZE);
                        }
                        output.write(buffer, 0, count);
                    }
                    if (size == 0) {
                        throw new IllegalArgumentException("빈 파일은 업로드할 수 없습니다.");
                    }
                    return new StoredFile(originalFilename, storedFilename, size);
                }
            }
        } catch (IOException | RuntimeException exception) {
            if (created) {
                removePartialFile(target, exception);
            }
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new FileStorageException("파일을 저장할 수 없습니다.", exception);
        }
    }

    public OpenedFile open(String storedFilename) {
        SeekableByteChannel channel = null;
        try {
            Path target = resolve(storedFilename);
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new FileStorageException("파일을 읽을 수 없습니다.");
            }
            channel = Files.newByteChannel(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            long size = channel.size();
            return new OpenedFile(new InputStreamResource(Channels.newInputStream(channel)), size);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            if (exception instanceof NoSuchFileException) {
                throw new ResourceNotFoundException("파일을 찾을 수 없습니다.");
            }
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new FileStorageException("파일을 읽을 수 없습니다.", exception);
        }
    }

    public void delete(String storedFilename) {
        try {
            Files.deleteIfExists(resolve(storedFilename));
        } catch (NoSuchFileException ignored) {
            // A missing storage directory means there is no physical file left to clean up.
        } catch (IOException exception) {
            throw new FileStorageException("파일을 삭제할 수 없습니다.", exception);
        }
    }

    private Path resolve(String storedFilename) throws IOException {
        if (storedFilename == null || !STORED_FILENAME.matcher(storedFilename).matches()) {
            throw new FileStorageException("저장된 파일 정보가 올바르지 않습니다.");
        }
        Path root = location.toRealPath();
        Path resolved = root.resolve(storedFilename).normalize();
        if (!resolved.getParent().equals(root)) {
            throw new FileStorageException("저장된 파일 정보가 올바르지 않습니다.");
        }
        return resolved;
    }

    private void removePartialFile(Path target, Exception originalException) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException cleanupException) {
            originalException.addSuppressed(cleanupException);
            log.error("Incomplete upload cleanup failed; manually remove stored file {} after resolving storage errors",
                    target.getFileName(), cleanupException);
        }
    }

    public record StoredFile(String originalFilename, String storedFilename, long size) {}

    public record OpenedFile(Resource resource, long size) {}
}
