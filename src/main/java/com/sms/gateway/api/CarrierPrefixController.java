package com.sms.gateway.api;

import com.sms.gateway.carrier.Carrier;
import com.sms.gateway.carrier.CarrierPrefix;
import com.sms.gateway.carrier.CarrierPrefixService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/prefixes")
public class CarrierPrefixController {
    private final CarrierPrefixService service;

    public CarrierPrefixController(CarrierPrefixService service) {
        this.service = service;
    }

    @GetMapping("/{carrier}")
    public List<CarrierPrefix> list(
            @PathVariable Carrier carrier,
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly
    ) {
        return service.list(carrier, activeOnly);
    }

    public record UpsertPrefixRequest(@NotBlank String prefix, Boolean active) {
    }

    @PostMapping("/{carrier}")
    public CarrierPrefix upsert(@PathVariable Carrier carrier, @RequestBody UpsertPrefixRequest req) {
        boolean active = (req.active() == null) || req.active();
        return service.upsert(carrier, req.prefix(), active);
    }

    @DeleteMapping("/{carrier}/{prefix}")
    public void deactivate(@PathVariable Carrier carrier, @PathVariable String prefix) {
        service.deactivate(carrier, prefix);
    }
}
