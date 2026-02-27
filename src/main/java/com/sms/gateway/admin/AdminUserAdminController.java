package com.sms.gateway.admin;

import com.sms.gateway.admin.dto.AdminUserResponse;
import com.sms.gateway.admin.dto.CreateAdminUserRequest;
import com.sms.gateway.admin.dto.UpdateAdminUserRequest;
import com.sms.gateway.adminuser.AdminUser;
import com.sms.gateway.adminuser.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/admin/admin-users")
public class AdminUserAdminController {

    private final AdminUserService service;

    public AdminUserAdminController(AdminUserService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AdminUserResponse> create(@RequestBody @Valid CreateAdminUserRequest req) {
        AdminUser created = service.create(
            req.getUsername(),
            req.getPassword(),
            req.getEnabled(),
            req.getFirstName(),
            req.getLastName(),
            req.getEmail(),
            req.getTitle(),
            req.getDepartment()
        );
        return ResponseEntity.created(URI.create("/api/admin/admin-users/" + created.getId()))
                .body(toResponse(created));
    }

    @GetMapping
    public Page<AdminUserResponse> list(
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Order.asc("id")));
        if (email == null || email.isBlank()) {
            return service.list(pageable).map(this::toResponse);
        }
        return service.listByEmail(email, pageable).map(this::toResponse);
    }

    @GetMapping("/{id}")
    public AdminUserResponse get(@PathVariable Long id) {
        return toResponse(service.get(id));
    }

    @PutMapping("/{id}")
    public AdminUserResponse update(@PathVariable Long id, @RequestBody UpdateAdminUserRequest req) {
        return toResponse(service.update(
                id,
                req.getUsername(),
                req.getPassword(),
                req.getEnabled(),
                req.getFirstName(),
                req.getLastName(),
                req.getEmail(),
                req.getTitle(),
                req.getDepartment()
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private AdminUserResponse toResponse(AdminUser adminUser) {
        return new AdminUserResponse(
                adminUser.getId(),
                adminUser.getUsername(),
            adminUser.getFirstName(),
            adminUser.getLastName(),
            adminUser.getEmail(),
            adminUser.getTitle(),
            adminUser.getDepartment(),
                adminUser.isEnabled(),
                adminUser.getCreatedAt(),
                adminUser.getUpdatedAt()
        );
    }
}
