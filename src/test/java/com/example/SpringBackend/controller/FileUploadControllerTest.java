package com.example.SpringBackend.controller;

import com.example.SpringBackend.exception.GlobalExceptionHandler;
import com.example.SpringBackend.exception.StorageFileNotFoundException;
import com.example.SpringBackend.service.FileLocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    private MockMvc mockMvc;

    @Mock
    private FileLocalStorageService storageService;

    @InjectMocks
    private FileUploadController fileUploadController;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(fileUploadController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setPatternParser(new PathPatternParser())
                .build();
    }

    @Test
    void shouldServeFileById() throws Exception {
        String fileContent = "content-by-id";
        Resource mockResource = new ByteArrayResource(fileContent.getBytes());

        Map<String, Object> serviceResponse = new HashMap<>();
        serviceResponse.put("resource", mockResource);
        serviceResponse.put("filename", "document.pdf");
        serviceResponse.put("contentType", MediaType.APPLICATION_PDF_VALUE);

        when(storageService.loadResponseByMetadataId(8L))
                .thenReturn(serviceResponse);

        mockMvc.perform(get("/api/files/8"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"document.pdf\""))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(fileContent.getBytes()));
    }

    @Test
    void shouldHandleFileUpload() throws Exception {
        MockMultipartFile mockFile1 = new MockMultipartFile(
                "files", "test1.txt", "text/plain", "hello world 1".getBytes()
        );
        MockMultipartFile mockFile2 = new MockMultipartFile(
                "files", "test2.txt", "text/plain", "hello world 2".getBytes()
        );

        mockMvc.perform(multipart("/api/files")
                        .file(mockFile1)
                        .file(mockFile2)
                        .param("todoId", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Successfully uploaded 2 files!"))
                .andExpect(jsonPath("$.todoId").value(1));

        verify(storageService, times(1))
                .store(any(MultipartFile[].class), eq(1L));
    }

    @Test
    void shouldReturn404WhenFileNotFoundById() throws Exception {
        when(storageService.loadResponseByMetadataId(999L))
                .thenThrow(new StorageFileNotFoundException("Metadata not found"));

        mockMvc.perform(get("/api/files/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteFileById_ShouldReturnOk_WhenFileExists() throws Exception {
        long fileId = 1L;
        doNothing().when(storageService).deleteByMetadataId(fileId);

        mockMvc.perform(delete("/api/files/{id}", fileId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("File with ID " + fileId + " successfully deleted!"));

        verify(storageService, times(1)).deleteByMetadataId(fileId);
    }

    @Test
    void deleteFileById_ShouldReturnNotFound_WhenFileDoesNotExist() throws Exception {
        long nonExistingId = 999L;
        doThrow(new StorageFileNotFoundException("File not found"))
                .when(storageService).deleteByMetadataId(nonExistingId);

        mockMvc.perform(delete("/api/files/{id}", nonExistingId))
                .andExpect(status().isNotFound());

        verify(storageService, times(1)).deleteByMetadataId(nonExistingId);
    }
}