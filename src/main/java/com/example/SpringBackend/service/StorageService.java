package com.example.SpringBackend.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface StorageService {

    void store(MultipartFile[] files, Long todoId);

    Resource loadAsResource(String filename);

    Map<String, Object> loadResponseByFilename(String filename);

    Map<String, Object> loadResponseByMetadataId(Long id);

    void deleteByMetadataId(Long id);

    void deletePhysicalFile(String storedFilename);

    List<String> loadAllDownloadUrls();
}

