package com.clinevo.inbox.api;

import com.clinevo.inbox.service.LiteratureScreeningService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/literature")
@CrossOrigin(origins = "${clinevo.cors-origin:http://localhost:4200}")
public class LiteratureController {
    private final LiteratureScreeningService literatureScreeningService;

    public LiteratureController(LiteratureScreeningService literatureScreeningService) {
        this.literatureScreeningService = literatureScreeningService;
    }

    @PostMapping(value = "/screen", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode screen(@RequestPart("files") List<MultipartFile> files) {
        return literatureScreeningService.screen(files);
    }
}
