package com.sms.gateway.admin;

import com.sms.gateway.admin.dto.*;
import com.sms.gateway.users.ApiClient;
import com.sms.gateway.users.ApiClientRepository;
import com.sms.gateway.users.ApiClientService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/admin/api-clients")
public class ApiClientAdminController {

    private final ApiClientService apiClientService;
    private final ApiClientRepository apiClientRepository;

    public ApiClientAdminController(ApiClientService apiClientService, ApiClientRepository apiClientRepository) {
        this.apiClientService = apiClientService;
        this.apiClientRepository = apiClientRepository;
    }

    @PostMapping
    public ResponseEntity<ApiClientResponse> create(@RequestBody @Valid CreateApiClientRequest req) {
        ApiClient created = apiClientService.create(req.getUsername(), req.getPassword(), req.getDescription());
        return ResponseEntity
                .created(URI.create("/api/admin/api-clients/" + created.getId()))
                .body(toResponse(created));
    }

    @GetMapping
    public List<ApiClientResponse> list() {
        return apiClientRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public ApiClientResponse get(@PathVariable Long id) {
        ApiClient client = apiClientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ApiClient not found"));
        return toResponse(client);
    }

    @PutMapping("/{id}")
    public ApiClientResponse updateDescription(@PathVariable Long id, @RequestBody @Valid UpdateDescriptionRequest req) {
        return toResponse(apiClientService.updateDescription(id, req.getDescription()));
    }

    @PutMapping("/{id}/block")
    public ApiClientResponse setBlocked(@PathVariable Long id, @RequestBody SetBlockedRequest req) {
        return toResponse(apiClientService.setBlocked(id, req.isBlocked()));
    }

    @PutMapping("/{id}/password")
    public ApiClientResponse resetPassword(@PathVariable Long id, @RequestBody @Valid ResetPasswordRequest req) {
        return toResponse(apiClientService.resetPassword(id, req.getPassword()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        apiClientService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private ApiClientResponse toResponse(ApiClient client) {
        return new ApiClientResponse(
                client.getId(),
                client.getUsername(),
                client.getDescription(),
                client.isBlocked(),
                client.getCreatedAt(),
                client.getUpdatedAt()
        );
    }
}
