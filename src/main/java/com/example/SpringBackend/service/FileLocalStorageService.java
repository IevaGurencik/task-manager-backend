package com.example.SpringBackend.service;

import com.example.SpringBackend.controller.FileUploadController;
import com.example.SpringBackend.exception.StorageException;
import com.example.SpringBackend.exception.StorageFileNotFoundException;
import com.example.SpringBackend.model.FileMetadataEntity;
import com.example.SpringBackend.model.ToDoEntity;
import com.example.SpringBackend.repository.StorageRepository;
import com.example.SpringBackend.repository.ToDoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Profile("!prod")
public class FileLocalStorageService implements StorageService {

    private final StorageRepository storageRepository;
    private final ToDoRepository todoRepository;
    private final Path rootLocation;

    public FileLocalStorageService(
            @Value("${storage.location}") String location,
            StorageRepository storageRepository,
            ToDoRepository todoRepository) {
        if (location == null || location.trim().isEmpty()) {
            throw new StorageException("File upload location cannot be empty.");
        }
        this.rootLocation = Paths.get(location).toAbsolutePath().normalize();
        this.storageRepository = storageRepository;
        this.todoRepository = todoRepository;
        init();
    }

    private void init() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new StorageException("Could not initialize storage location", e);
        }
    }

    @Override
    @Transactional
    public void store(MultipartFile[] files, Long todoId) {
        if (files == null || files.length == 0) {
            throw new StorageException("No files provided for upload.");
        }
        ToDoEntity todo = todoRepository.findById(todoId)
                .orElseThrow(() -> new StorageException("Cannot upload files. ToDo not found with id: " + todoId));

        Arrays.stream(files)
                .filter(file -> !file.isEmpty())
                .forEach(file -> {
                    String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
                    String fileExtension = StringUtils.getFilenameExtension(originalFilename);
                    String storedFilename = UUID.randomUUID() + (fileExtension != null ? "." + fileExtension : "");

                    this.storeFileToDisk(file, storedFilename);

                    FileMetadataEntity metadata = new FileMetadataEntity();
                    metadata.setFilename(originalFilename);
                    metadata.setStoredFilename(storedFilename);
                    metadata.setTodo(todo);
                    storageRepository.save(metadata);
                });
    }

    public void store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new StorageException("Failed to store empty file.");
        }
        String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        this.storeFileToDisk(file, originalFilename);
    }

    public FileMetadataEntity findMetadataById(Long id) {
        return storageRepository.findById(id)
                .orElseThrow(() -> new StorageFileNotFoundException("File metadata not found with id: " + id));
    }

    @Override
    public Resource loadAsResource(String filename) {
        try {
            Path file = rootLocation.resolve(filename).normalize();
            if (!file.getParent().equals(this.rootLocation)) {
                throw new StorageFileNotFoundException("Cannot read file outside current directory: " + filename);
            }
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new StorageFileNotFoundException("Could not read file: " + filename);
            }
        } catch (MalformedURLException e) {
            throw new StorageFileNotFoundException("Could not read file: " + filename, e);
        }
    }

    @Override
    public Map<String, Object> loadResponseByFilename(String filename) {
        Resource resource = loadAsResource(filename);
        String contentType = "application/octet-stream";
        try {
            String probed = Files.probeContentType(rootLocation.resolve(filename));
            if (probed != null) {
                contentType = probed;
            }
        } catch (IOException ignored) {
        }

        Map<String, Object> response = new HashMap<>();
        response.put("resource", resource);
        response.put("filename", resource.getFilename() != null ? resource.getFilename() : "file");
        response.put("contentType", contentType);
        return response;
    }

    @Override
    public List<String> loadAllDownloadUrls() {
        try (Stream<Path> walk = Files.walk(this.rootLocation, 1)) {
            return walk
                    .filter(path -> !path.equals(this.rootLocation))
                    .map(this.rootLocation::relativize)
                    .map(path -> MvcUriComponentsBuilder.fromMethodName(
                                    FileUploadController.class, "serveFile", path.getFileName().toString())
                            .build().toUri().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new StorageException("Failed to generate download URLs", e);
        }
    }

    @Override
    public Map<String, Object> loadResponseByMetadataId(Long id) {
        FileMetadataEntity metadata = findMetadataById(id);
        Map<String, Object> response = loadResponseByFilename(metadata.getStoredFilename());
        response.put("filename", metadata.getFilename() != null ? metadata.getFilename() : "file");
        return response;
    }

    @Override
    @Transactional
    public void deleteByMetadataId(Long id) {
        FileMetadataEntity metadata = storageRepository.findById(id)
                .orElseThrow(() -> new StorageFileNotFoundException("Could not find file with id: " + id));
        this.deletePhysicalFile(metadata.getStoredFilename());
        storageRepository.delete(metadata);
    }


    @Override
    public void deletePhysicalFile(String storedFilename) {
        try {
            Path fileToDelete = rootLocation.resolve(storedFilename).normalize();
            if (fileToDelete.getParent().equals(this.rootLocation)) {
                Files.deleteIfExists(fileToDelete);
            }
        } catch (IOException e) {
            System.err.println("Error while deleting the file: " + storedFilename);
        }
    }

    private void storeFileToDisk(MultipartFile file, String filename) {
        try {
            Path destinationFile = this.rootLocation.resolve(Paths.get(filename)).normalize();
            if (!destinationFile.getParent().equals(this.rootLocation)) {
                throw new StorageException("Cannot store file outside current directory.");
            }
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("Failed to store file " + filename, e);
        }
    }
}