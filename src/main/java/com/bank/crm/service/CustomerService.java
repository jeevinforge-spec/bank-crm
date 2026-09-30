package com.bank.crm.service;

import com.bank.crm.config.RequestContext;
import com.bank.crm.dto.CustomerRequest;
import com.bank.crm.dto.CustomerResponse;
import com.bank.crm.dto.PageResponse;
import com.bank.crm.exception.ConflictException;
import com.bank.crm.exception.NotFoundException;
import com.bank.crm.model.*;
import com.bank.crm.repository.CustomerRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CustomerService {

    private static final String ENTITY = "Customer";
    private static final Set<String> SORTABLE = Set.of("id", "customerNumber", "firstName", "lastName", "email",
            "city", "country", "accountType", "accountBalance", "creditScore", "kycStatus", "riskCategory",
            "customerStatus", "createdAt", "updatedAt");

    private final CustomerRepository repository;
    private final AuditService auditService;
    private final Validator validator;

    public CustomerService(CustomerRepository repository, AuditService auditService, Validator validator) {
        this.repository = repository;
        this.auditService = auditService;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> search(String query, AccountType accountType, KycStatus kycStatus,
                                                 RiskCategory riskCategory, int page, int size,
                                                 String sortBy, String direction) {
        Specification<Customer> spec = (root, q, cb) -> cb.conjunction();
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("customerNumber")), like),
                    cb.like(cb.lower(root.get("firstName")), like),
                    cb.like(cb.lower(root.get("lastName")), like),
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("city")), like)));
        }
        if (accountType != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("accountType"), accountType));
        if (kycStatus != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("kycStatus"), kycStatus));
        if (riskCategory != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("riskCategory"), riskCategory));

        String sortField = SORTABLE.contains(sortBy) ? sortBy : "id";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(dir, sortField));
        return PageResponse.of(repository.findAll(spec, pageable), CustomerResponse::from);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long id) {
        return CustomerResponse.from(find(id));
    }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        Customer customer = new Customer();
        apply(request, customer);
        if (customer.getCustomerNumber() == null || customer.getCustomerNumber().isBlank()) {
            customer.setCustomerNumber(generateCustomerNumber());
        }
        String user = RequestContext.currentUser();
        customer.setCreatedBy(user);
        customer.setUpdatedBy(user);
        validate(customer);

        if (repository.existsByCustomerNumber(customer.getCustomerNumber())) {
            throw rejected(AuditAction.CREATE, customer.getCustomerNumber(),
                    "Customer number " + customer.getCustomerNumber() + " already exists");
        }
        if (repository.existsByEmailIgnoreCase(customer.getEmail())) {
            throw rejected(AuditAction.CREATE, customer.getCustomerNumber(),
                    "Email " + customer.getEmail() + " is already registered");
        }

        Customer saved = repository.save(customer);
        auditService.record(AuditAction.CREATE, ENTITY, String.valueOf(saved.getId()),
                "Created customer " + saved.getCustomerNumber(), snapshot(saved));
        return CustomerResponse.from(saved);
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = find(id);
        if (request.version() != null && !request.version().equals(customer.getVersion())) {
            throw new ConflictException("Customer was modified by someone else. Reload and try again.");
        }
        Map<String, Object> before = snapshot(customer);
        String originalEmail = customer.getEmail();

        apply(request, customer);
        customer.setCustomerNumber((String) before.get("customerNumber")); // customer number is immutable
        customer.setUpdatedBy(RequestContext.currentUser());
        validate(customer);

        if (!customer.getEmail().equalsIgnoreCase(originalEmail) && repository.existsByEmailIgnoreCase(customer.getEmail())) {
            throw rejected(AuditAction.UPDATE, String.valueOf(id), "Email " + customer.getEmail() + " is already registered");
        }

        var changes = AuditService.diff(before, snapshot(customer));
        Customer saved = repository.saveAndFlush(customer);
        if (!changes.isEmpty()) {
            auditService.record(AuditAction.UPDATE, ENTITY, String.valueOf(id),
                    "Updated " + String.join(", ", changes.keySet()) + " on " + saved.getCustomerNumber(), changes);
        }
        return CustomerResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        Customer customer = find(id);
        Map<String, Object> before = snapshot(customer);
        repository.delete(customer);
        auditService.record(AuditAction.DELETE, ENTITY, String.valueOf(id),
                "Deleted customer " + customer.getCustomerNumber(), before);
    }

    /** Demo helper: wipe all customers so bulk loads can be re-run. Audited like everything else. */
    @Transactional
    public int purgeAll() {
        int deleted = repository.deleteAllInBulk();
        auditService.record(AuditAction.PURGE, ENTITY, null, "Purged " + deleted + " customers", null);
        return deleted;
    }

    private Customer find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Customer " + id + " not found"));
    }

    private ConflictException rejected(AuditAction action, String entityId, String reason) {
        auditService.recordIndependent(action, ENTITY, entityId, RequestContext.currentUser(),
                RequestContext.clientIp(), null, AuditOutcome.FAILURE, "Rejected: " + reason, null);
        return new ConflictException(reason);
    }

    private void validate(Customer customer) {
        Set<ConstraintViolation<Customer>> violations = validator.validate(customer);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    }

    private String generateCustomerNumber() {
        String number;
        do {
            number = "CN" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        } while (repository.existsByCustomerNumber(number));
        return number;
    }

    private static void apply(CustomerRequest r, Customer c) {
        c.setCustomerNumber(trim(r.customerNumber()) == null ? null : trim(r.customerNumber()).toUpperCase());
        c.setFirstName(trim(r.firstName()));
        c.setLastName(trim(r.lastName()));
        c.setEmail(trim(r.email()) == null ? null : trim(r.email()).toLowerCase());
        c.setPhone(trim(r.phone()));
        c.setDateOfBirth(r.dateOfBirth());
        c.setAddressLine(trim(r.addressLine()));
        c.setCity(trim(r.city()));
        c.setState(trim(r.state()));
        c.setPostalCode(trim(r.postalCode()));
        c.setCountry(trim(r.country()));
        c.setAccountType(r.accountType());
        c.setAccountBalance(r.accountBalance() == null ? java.math.BigDecimal.ZERO : r.accountBalance());
        c.setAnnualIncome(r.annualIncome());
        c.setCreditScore(r.creditScore());
        if (r.kycStatus() != null) c.setKycStatus(r.kycStatus());
        if (r.riskCategory() != null) c.setRiskCategory(r.riskCategory());
        if (r.customerStatus() != null) c.setCustomerStatus(r.customerStatus());
        c.setBranchCode(trim(r.branchCode()) == null ? null : trim(r.branchCode()).toUpperCase());
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Business fields only (no timestamps/version) - the basis for audit snapshots and diffs. */
    static Map<String, Object> snapshot(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerNumber", c.getCustomerNumber());
        m.put("firstName", c.getFirstName());
        m.put("lastName", c.getLastName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        m.put("dateOfBirth", c.getDateOfBirth() == null ? null : c.getDateOfBirth().toString());
        m.put("addressLine", c.getAddressLine());
        m.put("city", c.getCity());
        m.put("state", c.getState());
        m.put("postalCode", c.getPostalCode());
        m.put("country", c.getCountry());
        m.put("accountType", c.getAccountType());
        m.put("accountBalance", c.getAccountBalance());
        m.put("annualIncome", c.getAnnualIncome());
        m.put("creditScore", c.getCreditScore());
        m.put("kycStatus", c.getKycStatus());
        m.put("riskCategory", c.getRiskCategory());
        m.put("customerStatus", c.getCustomerStatus());
        m.put("branchCode", c.getBranchCode());
        return m;
    }
}
