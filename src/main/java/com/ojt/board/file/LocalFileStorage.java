package com.ojt.board.file;

import com.ojt.board.global.ResourceNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@Component
@Slf4j
public class LocalFileStorage {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final long MAX_REQUEST_SIZE = 50L * 1024 * 1024;
    public static final int MAX_FILES_PER_REQUEST = 5;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final long MAX_OOXML_UNCOMPRESSED_SIZE = 100L * 1024 * 1024;
    private static final int MAX_OOXML_ENTRIES = 10_000;
    private static final int MAX_CONTENT_TYPES_SIZE = 1024 * 1024;
    private static final Pattern STORED_FILENAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Map<String, AllowedFileType> ALLOWED_TYPES = allowedTypes();

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
        String originalFilename = safeOriginalFilename(file.getOriginalFilename());
        AllowedFileType fileType = allowedFileType(originalFilename);
        validateDeclaredContentType(file.getContentType(), fileType);
        return originalFilename;
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
        AllowedFileType fileType = allowedFileType(originalFilename);
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
                }
            }
            validateStoredContent(target, fileType);
            return new StoredFile(originalFilename, storedFilename, size(target), fileType.contentType());
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

    private static AllowedFileType allowedFileType(String originalFilename) {
        int dot = originalFilename.lastIndexOf('.');
        String extension = dot < 1 || dot == originalFilename.length() - 1
                ? "" : originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        AllowedFileType fileType = ALLOWED_TYPES.get(extension);
        if (fileType == null) {
            throw new IllegalArgumentException("허용되지 않는 파일 형식입니다.");
        }
        return fileType;
    }

    private static void validateDeclaredContentType(String declared, AllowedFileType fileType) {
        if (declared == null || declared.isBlank()) {
            return;
        }
        final String normalized;
        try {
            MediaType mediaType = MediaType.parseMediaType(declared);
            normalized = (mediaType.getType() + "/" + mediaType.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (InvalidMediaTypeException exception) {
            throw new IllegalArgumentException("파일의 MIME 형식이 올바르지 않습니다.");
        }
        if (!normalized.equals(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                && !fileType.declaredContentTypes().contains(normalized)) {
            throw new IllegalArgumentException("파일 확장자와 MIME 형식이 일치하지 않습니다.");
        }
    }

    private static void validateStoredContent(Path target, AllowedFileType fileType) throws IOException {
        if (!fileType.signatureValidator().test(target)) {
            throw new IllegalArgumentException("파일 확장자와 실제 내용이 일치하지 않습니다.");
        }
    }

    private static long size(Path target) throws IOException {
        return Files.size(target);
    }

    private static boolean isPlainText(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            int offset = startsWith(bytes, new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}) ? 3 : 0;
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            return text.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                    && codePoint != '\t' && codePoint != '\n' && codePoint != '\r');
        } catch (CharacterCodingException exception) {
            return false;
        } catch (IOException exception) {
            throw new FileStorageException("파일 형식을 확인할 수 없습니다.", exception);
        }
    }

    private static boolean hasPrefix(Path path, byte[] prefix) {
        try (InputStream input = Files.newInputStream(path)) {
            return startsWith(input.readNBytes(prefix.length), prefix);
        } catch (IOException exception) {
            throw new FileStorageException("파일 형식을 확인할 수 없습니다.", exception);
        }
    }

    private static boolean isImage(Path path, Set<String> expectedFormats) {
        try (ImageInputStream image = ImageIO.createImageInputStream(path.toFile())) {
            if (image == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(image, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return expectedFormats.contains(format) && width > 0 && height > 0
                        && (long) width * height <= MAX_IMAGE_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean isPdf(Path path) {
        try {
            long fileSize = Files.size(path);
            if (fileSize < 16 || !hasPrefix(path, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return false;
            int tailSize = Math.toIntExact(Math.min(fileSize, 4096));
            byte[] tail = new byte[tailSize];
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.position(fileSize - tailSize);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(tail);
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                    // Read the bounded PDF trailer window.
                }
            }
            String trailer = new String(tail, StandardCharsets.ISO_8859_1);
            int eof = trailer.lastIndexOf("%%EOF");
            return eof >= 0 && trailer.substring(eof + 5).chars().allMatch(Character::isWhitespace);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean isOoxml(Path path, String mainEntryName, String mainContentType) {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            ZipEntry contentTypes = null;
            boolean mainEntry = false;
            var entries = archive.entries();
            int entryCount = 0;
            long uncompressedSize = 0;
            while (entries.hasMoreElements()) {
                if (++entryCount > MAX_OOXML_ENTRIES) return false;
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (name.startsWith("/") || Arrays.asList(name.split("/")).contains("..")) return false;
                if (lowerName.endsWith("vbaproject.bin") || lowerName.contains("/embeddings/")) return false;
                long size = entry.getSize();
                if (size < 0 || size > MAX_OOXML_UNCOMPRESSED_SIZE
                        || uncompressedSize > MAX_OOXML_UNCOMPRESSED_SIZE - size) return false;
                uncompressedSize += size;
                if (name.equals("[Content_Types].xml")) contentTypes = entry;
                if (name.equals(mainEntryName) && !entry.isDirectory() && size > 0) mainEntry = true;
            }
            return mainEntry && contentTypes != null
                    && hasOoxmlContentType(archive, contentTypes, mainEntryName, mainContentType);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean hasOoxmlContentType(ZipFile archive, ZipEntry entry, String mainEntryName,
                                                String mainContentType) {
        if (entry.getSize() < 1 || entry.getSize() > MAX_CONTENT_TYPES_SIZE) return false;
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] xml = input.readNBytes(MAX_CONTENT_TYPES_SIZE + 1);
            if (xml.length > MAX_CONTENT_TYPES_SIZE) return false;
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
            var overrides = document.getElementsByTagNameNS("*", "Override");
            for (int index = 0; index < overrides.getLength(); index++) {
                var element = (org.w3c.dom.Element) overrides.item(index);
                if (element.getAttribute("PartName").equals("/" + mainEntryName)
                        && element.getAttribute("ContentType").equals(mainContentType)) return true;
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, AllowedFileType> allowedTypes() {
        Map<String, AllowedFileType> types = new HashMap<>();
        register(types, Set.of("txt"), MediaType.TEXT_PLAIN_VALUE, LocalFileStorage::isPlainText,
                Set.of(MediaType.TEXT_PLAIN_VALUE));
        register(types, Set.of("csv"), "text/csv", LocalFileStorage::isPlainText,
                Set.of("text/csv", "application/csv", "application/vnd.ms-excel", MediaType.TEXT_PLAIN_VALUE));
        register(types, Set.of("png"), MediaType.IMAGE_PNG_VALUE,
                path -> isImage(path, Set.of("png")),
                Set.of(MediaType.IMAGE_PNG_VALUE));
        register(types, Set.of("jpg", "jpeg"), MediaType.IMAGE_JPEG_VALUE,
                path -> isImage(path, Set.of("jpeg", "jpg")),
                Set.of(MediaType.IMAGE_JPEG_VALUE, "image/pjpeg"));
        register(types, Set.of("gif"), MediaType.IMAGE_GIF_VALUE,
                path -> isImage(path, Set.of("gif")),
                Set.of(MediaType.IMAGE_GIF_VALUE));
        register(types, Set.of("pdf"), MediaType.APPLICATION_PDF_VALUE, LocalFileStorage::isPdf,
                Set.of(MediaType.APPLICATION_PDF_VALUE));
        register(types, Set.of("docx"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                path -> isOoxml(path, "word/document.xml",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"),
                Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/zip", "application/x-zip-compressed"));
        register(types, Set.of("xlsx"),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                path -> isOoxml(path, "xl/workbook.xml",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"),
                Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/zip", "application/x-zip-compressed"));
        register(types, Set.of("pptx"),
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                path -> isOoxml(path, "ppt/presentation.xml",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"),
                Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/zip", "application/x-zip-compressed"));
        return Map.copyOf(types);
    }

    private static void register(Map<String, AllowedFileType> types, Set<String> extensions, String contentType,
                                 Predicate<Path> signatureValidator, Set<String> declaredContentTypes) {
        AllowedFileType fileType = new AllowedFileType(contentType, signatureValidator, declaredContentTypes);
        extensions.forEach(extension -> types.put(extension, fileType));
    }

    private record AllowedFileType(String contentType, Predicate<Path> signatureValidator,
                                   Set<String> declaredContentTypes) {}

    public record StoredFile(String originalFilename, String storedFilename, long size, String contentType) {}

    public record OpenedFile(Resource resource, long size) {}
}
