package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.CustomerService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customers;
    private final CapabilityService caps;

    public CustomerController(CustomerService customers, CapabilityService caps) {
        this.customers = customers; this.caps = caps;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.CUSTOMER_MANAGE);
        return Map.of("customerId", customers.create(body));
    }
}
