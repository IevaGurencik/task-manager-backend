package com.example.SpringBackend.service;

import com.example.SpringBackend.exception.StorageException;
import com.example.SpringBackend.exception.StorageFileNotFoundException;
import com.example.SpringBackend.model.FileMetadataEntity;
import com.example.SpringBackend.model.ToDoEntity;
import com.example.SpringBackend.repository.StorageRepository;
import com.example.SpringBackend.repository.ToDoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.empty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {

    private FileLocalStorageService storageService;

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private ToDoRepository todoRepository;

    @TempDir
    Path sharedTempDir;

    @BeforeEach
    void setUp() {
        String location = sharedTempDir.toString();
        storageService = new FileLocalStorageService(location, storageRepository, todoRepository);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void shouldSaveUploadedFile() throws IOException {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "Spring Framework".getBytes()
        );

        storageService.store(multipartFile);

        Path uploadedFile = sharedTempDir.resolve("test.txt");
        assertThat(Files.exists(uploadedFile)).isTrue();
        assertThat(Files.readString(uploadedFile)).isEqualTo("Spring Framework");
    }

    @Test
    void shouldThrowExceptionWhenToDoNotFoundOnStore() {
        Long todoId = 99L;
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "test.txt",
                "text/plain",
                "Hello, World!".getBytes()
        );
        MultipartFile[] files = new MultipartFile[]{file};

        when(todoRepository.findById(todoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storageService.store(files, todoId))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Cannot upload files. ToDo not found with id: " + todoId);
    }

    @Test
    void shouldThrowExceptionForRelativePathAttack() {
        MockMultipartFile maliciousFile = new MockMultipartFile(
                "file",
                "../malicious.txt",
                "text/plain",
                "attack".getBytes()
        );

        assertThatThrownBy(() -> storageService.store(maliciousFile))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Cannot store file outside current directory");
    }

    @Test
    void shouldListAllFilesAndUrls() throws IOException {
        Files.writeString(sharedTempDir.resolve("first.txt"), "first");
        Files.writeString(sharedTempDir.resolve("second.txt"), "second");

        List<String> urls = storageService.loadAllDownloadUrls();
        assertThat(urls).hasSize(2);
        assertThat(urls).containsExactlyInAnyOrder(
                "http://localhost/api/files/first.txt",
                "http://localhost/api/files/second.txt"
        );
    }

    @Test
    void shouldThrow404WhenFileNotFound() {
        assertThatThrownBy(() -> storageService.loadAsResource("non-existent.txt"))
                .isInstanceOf(StorageFileNotFoundException.class)
                .hasMessageContaining("Could not read file: non-existent.txt");
    }

    @Test
    void shouldSaveMultipleFilesAndAssignToTodo() throws IOException {
        ToDoEntity mockTodo = new ToDoEntity();
        when(todoRepository.findById(1L)).thenReturn(Optional.of(mockTodo));

        MockMultipartFile file1 = new MockMultipartFile("files", "doc1.pdf", "application/pdf", "pdf data".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("files", "img2.png", "image/png", "png data".getBytes());
        MultipartFile[] filesArray = new MultipartFile[]{file1, file2};

        storageService.store(filesArray, 1L);

        verify(storageRepository, times(2))
                .save(any(FileMetadataEntity.class));

        long createdFilesCount = Files.walk(sharedTempDir, 1)
                .filter(path -> !path.equals(sharedTempDir))
                .count();
        assertThat(createdFilesCount).isEqualTo(2);
    }

    @Test
    void shouldThrowExceptionWhenTodoNotFoundOnUpload() {
        when(todoRepository.findById(99L)).thenReturn(empty());
        MultipartFile[] emptyArray = new MultipartFile[]{
                new MockMultipartFile("files", "test.txt", "text/plain", "data".getBytes())
        };

        assertThatThrownBy(() -> storageService.store(emptyArray, 99L))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Cannot upload files. ToDo not found with id: 99");

        verify(storageRepository, never()).save(any());
    }

    @Test
    void shouldLoadResponseByMetadataId_Success() throws IOException {
        Long metadataId = 1L;
        String fakeUuidName = "generated-uuid-name.txt";

        Files.writeString(sharedTempDir.resolve(fakeUuidName), "file content inside uuid");

        FileMetadataEntity metadata = new FileMetadataEntity();
        metadata.setFilename("user-original-name.txt");
        metadata.setStoredFilename(fakeUuidName);

        when(storageRepository.findById(metadataId)).thenReturn(Optional.of(metadata));

        Map<String, Object> response = storageService.loadResponseByMetadataId(metadataId);

        assertThat(response).isNotNull();
        assertThat(response.get("filename")).isEqualTo("user-original-name.txt");
        assertThat(response.get("contentType")).isEqualTo("text/plain");

        Resource resource = (Resource) response.get("resource");
        assertThat(resource.exists()).isTrue();
        assertThat(resource.isReadable()).isTrue();
    }

    @Test
    void shouldThrow404WhenMetadataIdDoesNotExistInDb() {
        Long fakeId = 555L;
        when(storageRepository.findById(fakeId)).thenReturn(empty());

        assertThatThrownBy(() -> storageService.loadResponseByMetadataId(fakeId))
                .isInstanceOf(StorageFileNotFoundException.class)
                .hasMessageContaining("File metadata not found with id: 555");
    }

    @Test
    void shouldLoadResponseByFilename_Success() throws IOException {
        String filename = "direct-file.txt";
        Files.writeString(sharedTempDir.resolve(filename), "direct access content");

        Map<String, Object> response = storageService.loadResponseByFilename(filename);

        assertThat(response).isNotNull();
        assertThat(response.get("filename")).isEqualTo(filename);
        assertThat(response.get("contentType")).isEqualTo("text/plain");

        Resource resource = (Resource) response.get("resource");
        assertThat(resource.exists()).isTrue();
    }

    @Test
    void shouldThrow404WhenFilenameDoesNotExistOnDisk() {
        String missingFilename = "ghost-file.jpg";

        assertThatThrownBy(() -> storageService.loadResponseByFilename(missingFilename))
                .isInstanceOf(StorageFileNotFoundException.class)
                .hasMessageContaining("Could not read file: " + missingFilename);
    }

    @Test
    void shouldDeleteFileByMetadataId_Success() throws IOException {
        Long metadataId = 42L;
        String fakeUuidName = "delete-uuid-name.txt";
        Path physicalFile = sharedTempDir.resolve(fakeUuidName);
        Files.writeString(physicalFile, "temporary content to be deleted");

        FileMetadataEntity metadata = new FileMetadataEntity();
        metadata.setId(metadataId);
        metadata.setStoredFilename(fakeUuidName);

        when(storageRepository.findById(metadataId)).thenReturn(Optional.of(metadata));
        storageService.deleteByMetadataId(metadataId);

        assertThat(Files.exists(physicalFile)).isFalse();

        verify(storageRepository, times(1))
                .delete(any(FileMetadataEntity.class));
    }

    @Test
    void deleteByMetadataId_ShouldThrowException_WhenIdNotFoundInDb() {
        Long missingId = 777L;
        when(storageRepository.findById(missingId)).thenReturn(empty());

        assertThatThrownBy(() -> storageService.deleteByMetadataId(missingId))
                .isInstanceOf(StorageFileNotFoundException.class)
                .hasMessageContaining("Could not find file with id: 777");

        verify(storageRepository, never())
                .delete(any(FileMetadataEntity.class));
    }

    @Test
    void shouldDeletePhysicalFileDirectly() throws IOException {
        String filename = "physical-target.txt";
        Path physicalFile = sharedTempDir.resolve(filename);
        Files.writeString(physicalFile, "content to test cascading physical file removal");
        assertThat(Files.exists(physicalFile)).isTrue();

        Long testId = 1L;
        FileMetadataEntity mockMetadata = new FileMetadataEntity();
        mockMetadata.setId(testId);
        mockMetadata.setStoredFilename(filename);

        when(storageRepository.findById(testId)).thenReturn(Optional.of(mockMetadata));

        storageService.deleteByMetadataId(testId);

        assertThat(Files.exists(physicalFile)).isFalse();
        verify(storageRepository).delete(mockMetadata);
    }
}