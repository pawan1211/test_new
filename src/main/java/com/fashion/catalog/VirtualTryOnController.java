package com.fashion.catalog;

import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class VirtualTryOnController {
    private final VirtualTryOnService service;
    public VirtualTryOnController(VirtualTryOnService service) { this.service=service; }

    @GetMapping("/try-on/status")
    public Map<String,Object> status() { return Map.of("available",service.available(),"message",service.availabilityMessage()); }

    @PostMapping(value="/virtual-tryon/generate", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VirtualTryOnService.JobResponse> generate(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam @NotNull UUID productId,
            @RequestParam(required=false) UUID variantId,
            @RequestParam(defaultValue="false") boolean consent,
            @RequestPart("photo") MultipartFile photo) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.generate(authorization,productId,variantId,consent,photo));
    }

    @GetMapping("/virtual-tryon/{jobId}")
    public VirtualTryOnService.JobResponse status(@RequestHeader(value="Authorization",required=false) String authorization,
                                                   @PathVariable UUID jobId) {
        return service.status(authorization,jobId);
    }

    @GetMapping("/virtual-tryon/{jobId}/result")
    public ResponseEntity<Resource> result(@RequestHeader(value="Authorization",required=false) String authorization,
                                           @PathVariable UUID jobId) {
        VirtualTryOnService.ResultFile file=service.result(authorization,jobId);
        MediaType type="image/jpeg".equalsIgnoreCase(file.contentType())?MediaType.IMAGE_JPEG:MediaType.IMAGE_PNG;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"atelier-tryon-preview\"")
                .body(new FileSystemResource(file.path()));
    }
}
