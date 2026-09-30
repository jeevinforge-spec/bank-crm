package com.bank.crm.controller;

import com.bank.crm.dto.CustomerRequest;
import com.bank.crm.dto.CustomerResponse;
import com.bank.crm.dto.PageResponse;
import com.bank.crm.model.AccountType;
import com.bank.crm.model.KycStatus;
import com.bank.crm.model.RiskCategory;
import com.bank.crm.service.CustomerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public PageResponse<CustomerResponse> search(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) AccountType accountType,
                                                 @RequestParam(required = false) KycStatus kycStatus,
                                                 @RequestParam(required = false) RiskCategory riskCategory,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "25") int size,
                                                 @RequestParam(defaultValue = "id") String sort,
                                                 @RequestParam(defaultValue = "desc") String dir) {
        return customerService.search(q, accountType, kycStatus, riskCategory, page, size, sort, dir);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable Long id) {
        return customerService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@RequestBody CustomerRequest request) {
        return customerService.create(request);
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @RequestBody CustomerRequest request) {
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        customerService.delete(id);
    }

    @DeleteMapping
    public Map<String, Integer> purge(@RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) throw new IllegalArgumentException("Pass confirm=true to delete ALL customers");
        return Map.of("deleted", customerService.purgeAll());
    }
}
