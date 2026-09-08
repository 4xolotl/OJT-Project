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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        MockMultipartFile multipart = new MockMultipartFile("files", "C:\\fakepath\\보고서 1.txt", "text/html", bytes);
        LocalFileStorage.StoredFile first = storage.store(multipart);
        LocalFileStorage.StoredFile second = storage.store(multipart);

        assertEquals("보고서 1.txt", first.originalFilename());
        assertNotEquals(first.storedFilename(), second.storedFilename());
        assertTrue(first.storedFilename().matches("[0-9a-f-]{36}"));
        assertEquals(bytes.length, first.size());
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
        LocalFileStorage.StoredFile stored = storage.store(new MockMultipartFile("files", "data.bin", null, bytes));
        assertEquals(LocalFileStorage.MAX_FILE_SIZE, stored.size());
        assertEquals(LocalFileStorage.MAX_FILE_SIZE, Files.size(temporaryDirectory.resolve(stored.storedFilename())));
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
}
