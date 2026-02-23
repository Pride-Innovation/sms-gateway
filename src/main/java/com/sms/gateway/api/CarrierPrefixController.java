package com.sms.gateway.api;

import com.sms.gateway.carrier.Carrier;
import com.sms.gateway.carrier.CarrierPrefix;
import com.sms.gateway.carrier.CarrierPrefixService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/prefixes")
public class CarrierPrefixController {
    private final CarrierPrefixService service;

    public CarrierPrefixController(CarrierPrefixService service) {
        this.service = service;
    }

    @GetMapping
    public Page<CarrierPrefix> listAll(
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Order.asc("carrier"), Sort.Order.asc("prefix")));
        return service.listAllPage(activeOnly, pageable);
    }

    @GetMapping("/{carrier}")
    public Page<CarrierPrefix> list(
            @PathVariable Carrier carrier,
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Order.asc("prefix")));
        return service.listPage(carrier, activeOnly, pageable);
    }

    public record UpsertPrefixRequest(@NotBlank String prefix, Boolean active) {
    }

    @PostMapping("/{carrier}")
    public CarrierPrefix create(@PathVariable Carrier carrier, @RequestBody UpsertPrefixRequest req) {
        boolean active = (req.active() == null) || req.active();
        return service.create(carrier, req.prefix(), active);
    }

    public record UpdatePrefixRequest(String prefix, Boolean active) {
    }

    @PutMapping("/{carrier}/{prefix}")
    public CarrierPrefix update(
            @PathVariable Carrier carrier,
            @PathVariable String prefix,
            @RequestBody UpdatePrefixRequest req
    ) {
        return service.update(carrier, prefix, req.prefix(), req.active());
    }

    @DeleteMapping("/{carrier}/{prefix}")
    public void deactivate(@PathVariable Carrier carrier, @PathVariable String prefix) {
        service.deactivate(carrier, prefix);
    }
}
