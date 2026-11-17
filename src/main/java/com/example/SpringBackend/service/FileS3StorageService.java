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
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Profile("prod")
public class FileS3StorageService implements StorageService {

    private final StorageRepository storageRepository;
    private final ToDoRepository todoRepository;
    private final S3Client s3Client;
    private final String bucketName;

    public FileS3StorageService(
            S3Client s3Client,
            @Value("${aws.s3.bucket}") String bucketName,
            StorageRepository storageRepository,
            ToDoRepository todoRepository) {
        if (bucketName == null || bucketName.trim().isEmpty()) {
            throw new StorageException("S3 bucket name cannot be empty.");
        }
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.storageRepository = storageRepository;
        this.todoRepository = todoRepository;
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

                    this.uploadFileToS3(file, storedFilename);

                    FileMetadataEntity metadata = new FileMetadataEntity();
                    metadata.setFilename(originalFilename);
                    metadata.setStoredFilename(storedFilename);
                    metadata.setTodo(todo);

                    storageRepository.save(metadata);
                });
    }

    @Override
    public Resource loadAsResource(String filename) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(filename)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);
            return new InputStreamResource(s3Object);
        } catch (NoSuchKeyException e) {
            throw new StorageFileNotFoundException("Could not read file: " + filename, e);
        } catch (S3Exception e) {
            throw new StorageException("Failed to load file from S3: " + filename, e);
        }
    }

    @Override
    public Map<String, Object> loadResponseByFilename(String filename) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(filename)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);
            String contentType = s3Object.response().contentType() != null
                    ? s3Object.response().contentType()
                    : "application/octet-stream";

            Map<String, Object> response = new HashMap<>();
            response.put("resource", new InputStreamResource(s3Object));
            response.put("filename", filename);
            response.put("contentType", contentType);
            return response;
        } catch (NoSuchKeyException e) {
            throw new StorageFileNotFoundException("Could not read file: " + filename, e);
        } catch (S3Exception e) {
            throw new StorageException("Failed to read file from S3: " + filename, e);
        }
    }

    @Override
    public Map<String, Object> loadResponseByMetadataId(Long id) {
        FileMetadataEntity metadata = storageRepository.findById(id)
                .orElseThrow(() -> new StorageFileNotFoundException("File metadata not found with id: " + id));

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
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(storedFilename)
                    .build();
            s3Client.deleteObject(deleteRequest);
        } catch (S3Exception e) {
            System.err.println("Error while deleting the file from S3: " + storedFilename);
        }
    }

    @Override
    public List<String> loadAllDownloadUrls() {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .build();

            return s3Client.listObjectsV2(listRequest).contents().stream()
                    .map(S3Object::key)
                    .map(key -> MvcUriComponentsBuilder
                            .fromMethodName(FileUploadController.class, "serveFile", key)
                            .build()
                            .toUri()
                            .toString())
                    .collect(Collectors.toList());
        } catch (S3Exception e) {
            throw new StorageException("Failed to generate download URLs", e);
        }
    }

    private void uploadFileToS3(MultipartFile file, String key) {
        try (InputStream inputStream = file.getInputStream()) {
            String contentType = file.getContentType() != null
                    ? file.getContentType()
                    : "application/octet-stream";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(inputStream, file.getSize()));
        } catch (IOException | S3Exception e) {
            throw new StorageException("Failed to upload file " + key, e);
        }
    }
}