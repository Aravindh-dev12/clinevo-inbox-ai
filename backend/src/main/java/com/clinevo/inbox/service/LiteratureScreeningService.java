package com.clinevo.inbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class LiteratureScreeningService {
    private final RestClient restClient;
    private final long maxAttachmentBytes;

    public LiteratureScreeningService(
            RestClient.Builder restClientBuilder,
            @Value("${clinevo.ai-service-url}") String aiServiceUrl,
            @Value("${clinevo.max-attachment-bytes:20971520}") long maxAttachmentBytes
    ) {
        this.restClient = restClientBuilder.baseUrl(aiServiceUrl).build();
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    public JsonNode screen(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one article PDF is required");
        }
        if (files.size() > 25) {
            throw new IllegalArgumentException("A maximum of 25 article PDFs can be screened in one batch");
        }

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        for (MultipartFile file : files) {
            validate(file);
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read uploaded article PDF", ex);
            }
            body.part("files", new ByteArrayResource(bytes) {
                @Override public String getFilename() {
                    return file.getOriginalFilename() == null ? "article.pdf" : file.getOriginalFilename();
                }
            }).contentType(MediaType.APPLICATION_PDF);
        }

        JsonNode response = restClient.post()
                .uri("/literature/screen")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new IllegalStateException("AI literature screening service returned an empty response");
        }
        return response;
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Article PDF must not be empty");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!"application/pdf".equals(type) && !name.endsWith(".pdf")) {
            throw new IllegalArgumentException("Literature screening accepts PDF files only");
        }
        if (file.getSize() > maxAttachmentBytes) {
            throw new IllegalArgumentException("Article PDF exceeds the configured attachment size limit");
        }
    }
}
