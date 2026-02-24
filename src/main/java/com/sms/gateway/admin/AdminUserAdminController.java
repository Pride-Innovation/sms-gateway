package com.sms.gateway.admin;

import com.sms.gateway.admin.dto.AdminUserResponse;
import com.sms.gateway.admin.dto.CreateAdminUserRequest;
import com.sms.gateway.admin.dto.UpdateAdminUserRequest;
import com.sms.gateway.adminuser.AdminUser;
import com.sms.gateway.adminuser.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

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
    public List<AdminUserResponse> list() {
        return service.list().stream().map(this::toResponse).toList();
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
