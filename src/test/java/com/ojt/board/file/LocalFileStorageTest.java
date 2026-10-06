package com.ojt.board.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ojt.board.global.ResourceNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

class LocalFileStorageTest {

    @TempDir
    Path temporaryDirectory;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(temporaryDirectory.toString());
    }

    @Test
    void storesDistinctRandomNamesAndStreamsUnchangedBytes() throws Exception {
        byte[] bytes = "첨부파일 내용".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile multipart = new MockMultipartFile("files", "C:\\fakepath\\보고서 1.txt", "text/plain", bytes);
        LocalFileStorage.StoredFile first = storage.store(multipart);
        LocalFileStorage.StoredFile second = storage.store(multipart);

        assertEquals("보고서 1.txt", first.originalFilename());
        assertNotEquals(first.storedFilename(), second.storedFilename());
        assertTrue(first.storedFilename().matches("[0-9a-f-]{36}"));
        assertEquals(bytes.length, first.size());
        assertEquals("text/plain", first.contentType());
        LocalFileStorage.OpenedFile download = storage.open(first.storedFilename());
        assertEquals(bytes.length, download.size());
        try (InputStream stream = download.resource().getInputStream()) {
            assertArrayEquals(bytes, stream.readAllBytes());
        }

        storage.delete(first.storedFilename());
        storage.delete(first.storedFilename());
        assertFalse(Files.exists(temporaryDirectory.resolve(first.storedFilename())));
        assertTrue(Files.exists(temporaryDirectory.resolve(second.storedFilename())));
        assertThrows(ResourceNotFoundException.class, () -> storage.open(first.storedFilename()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ".", "..", "../secret.txt", "..\\secret.txt", "folder/../secret.txt", "name\r\n.txt", "name\u0000.txt", "folder/"})
    void rejectsInvalidOriginalNames(String originalFilename) {
        assertThrows(IllegalArgumentException.class, () -> LocalFileStorage.safeOriginalFilename(originalFilename));
    }

    @Test
    void stripsAbsoluteClientPathsAndRejectsLongNames() {
        assertEquals("photo.png", LocalFileStorage.safeOriginalFilename("/home/user/photo.png"));
        assertEquals("photo.png", LocalFileStorage.safeOriginalFilename("C:\\fakepath\\photo.png"));
        assertThrows(IllegalArgumentException.class, () -> LocalFileStorage.safeOriginalFilename("a".repeat(256)));
    }

    @Test
    void doesNotResolveClientControlledStoragePaths() {
        assertThrows(FileStorageException.class, () -> storage.open("../outside.txt"));
        assertThrows(FileStorageException.class, () -> storage.delete("C:\\outside.txt"));
    }

    @Test
    void removesPartiallyWrittenFileWhenInputFails() throws Exception {
        MultipartFile multipart = mock(MultipartFile.class);
        when(multipart.getOriginalFilename()).thenReturn("report.txt");
        when(multipart.getSize()).thenReturn(10L);
        when(multipart.getInputStream()).thenReturn(new InputStream() {
            private int bytesRead;

            @Override
            public int read() throws IOException {
                if (bytesRead++ < 4) {
                    return 'a';
                }
                throw new IOException("simulated input failure");
            }
        });

        assertThrows(FileStorageException.class, () -> storage.store(multipart));
        assertEquals(0, storedFileCount());
    }

    @Test
    void checksStreamSizeEvenWhenReportedSizeIsIncorrect() throws Exception {
        MultipartFile multipart = mock(MultipartFile.class);
        when(multipart.getOriginalFilename()).thenReturn("report.txt");
        when(multipart.getSize()).thenReturn(1L);
        when(multipart.getInputStream()).thenReturn(
                new ByteArrayInputStream(new byte[(int) LocalFileStorage.MAX_FILE_SIZE + 1]));

        assertThrows(MaxUploadSizeExceededException.class, () -> storage.store(multipart));
        assertEquals(0, storedFileCount());
    }

    @Test
    void acceptsFileExactlyAtSizeLimit() throws Exception {
        byte[] bytes = new byte[(int) LocalFileStorage.MAX_FILE_SIZE];
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);
        byte[] trailer = "startxref\n0\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(trailer, 0, bytes, bytes.length - trailer.length, trailer.length);
        LocalFileStorage.StoredFile stored = storage.store(
                new MockMultipartFile("files", "report.pdf", "application/pdf", bytes));
        assertEquals(LocalFileStorage.MAX_FILE_SIZE, stored.size());
        assertEquals(LocalFileStorage.MAX_FILE_SIZE, Files.size(temporaryDirectory.resolve(stored.storedFilename())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"attack.html", "attack.htm", "attack.svg", "attack.js", "attack.mjs", "attack.xml",
            "attack.exe", "macro.docm", "macro.xlsm", "macro.pptm", "legacy.doc", "legacy.hwp", "no-extension"})
    void rejectsActiveAndUnapprovedExtensions(String filename) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> storage.store(new MockMultipartFile(
                "files", filename, "application/octet-stream", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))));
        assertEquals(0, storedFileCount());
    }

    @Test
    void rejectsDeclaredMimeThatDoesNotMatchTheExtension() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> storage.store(new MockMultipartFile(
                "files", "notes.txt", "text/html", "ordinary notes".getBytes(StandardCharsets.UTF_8))));
        assertEquals(0, storedFileCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake.png", "fake.jpg", "fake.pdf", "fake.docx"})
    void rejectsFilesWhoseBytesDoNotMatchTheirAllowedExtension(String filename) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> storage.store(new MockMultipartFile(
                "files", filename, "application/octet-stream", "not the claimed format".getBytes(StandardCharsets.UTF_8))));
        assertEquals(0, storedFileCount());
    }

    @Test
    void rejectsTruncatedFilesThatOnlyCarryARecognizablePrefix() throws Exception {
        byte[] pngPrefix = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        assertThrows(IllegalArgumentException.class, () -> storage.store(
                new MockMultipartFile("files", "truncated.png", "image/png", pngPrefix)));
        assertThrows(IllegalArgumentException.class, () -> storage.store(
                new MockMultipartFile("files", "truncated.pdf", "application/pdf",
                        "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(0, storedFileCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<!doctype html><title>x</title>", "<svg onload=alert(1)></svg>",
            "plain text before <script>alert(1)</script>"})
    void storesMarkupAsCanonicalPlainTextForSafeAttachmentDownload(String content) throws Exception {
        LocalFileStorage.StoredFile stored = storage.store(new MockMultipartFile(
                "files", "renamed.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8)));
        assertEquals("text/plain", stored.contentType());
        assertEquals(1, storedFileCount());
    }

    @Test
    void acceptsVerifiedImageAndOfficeDocumentSignatures() throws Exception {
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", imageBytes));
        LocalFileStorage.StoredFile image = storage.store(
                new MockMultipartFile("files", "photo.png", "image/png", imageBytes.toByteArray()));
        LocalFileStorage.StoredFile document = storage.store(new MockMultipartFile("files", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ooxml("word/document.xml")));

        assertEquals("image/png", image.contentType());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                document.contentType());
        assertEquals(2, storedFileCount());
    }

    @Test
    void failsClearlyWhenStorageLocationIsAFile() throws Exception {
        Path occupied = Files.writeString(temporaryDirectory.resolve("occupied"), "existing data");
        LocalFileStorage unavailableStorage = new LocalFileStorage(occupied.toString());
        assertThrows(FileStorageException.class, () -> unavailableStorage.store(
                new MockMultipartFile("files", "data.txt", null, new byte[]{1})));
        assertEquals("existing data", Files.readString(occupied));
    }

    private long storedFileCount() throws IOException {
        try (var files = Files.list(temporaryDirectory)) {
            return files.count();
        }
    }

    private byte[] ooxml(String applicationEntry) throws IOException {
        String contentType = applicationEntry.startsWith("word/")
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                : applicationEntry.startsWith("xl/")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                : "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/" + applicationEntry + "\" ContentType=\"" + contentType + "\"/>"
                + "</Types>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            for (String entryName : Arrays.asList("[Content_Types].xml", applicationEntry)) {
                archive.putNextEntry(new ZipEntry(entryName));
                archive.write((entryName.equals("[Content_Types].xml") ? types : "<document/>")
                        .getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
