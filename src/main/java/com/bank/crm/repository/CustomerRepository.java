package com.bank.crm.repository;

import com.bank.crm.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    boolean existsByCustomerNumber(String customerNumber);

    boolean existsByEmailIgnoreCase(String email);

    /** Used by bulk-load workers to pre-screen a whole chunk for duplicates in one query. */
    @Query("select c.customerNumber from Customer c where c.customerNumber in :numbers")
    List<String> findExistingCustomerNumbers(@Param("numbers") Collection<String> numbers);

    /** Emails are always stored lower-cased, so this can use the unique index directly. */
    @Query("select c.email from Customer c where c.email in :emails")
    List<String> findExistingEmails(@Param("emails") Collection<String> emails);

    @Query("select coalesce(sum(c.accountBalance), 0) from Customer c")
    BigDecimal totalBalance();

    @Query("select c.accountType, count(c), coalesce(sum(c.accountBalance), 0) from Customer c group by c.accountType")
    List<Object[]> statsByAccountType();

    @Query("select c.kycStatus, count(c) from Customer c group by c.kycStatus")
    List<Object[]> countByKycStatus();

    @Query("select c.riskCategory, count(c) from Customer c group by c.riskCategory")
    List<Object[]> countByRiskCategory();

    @Modifying
    @Query("delete from Customer c")
    int deleteAllInBulk();
}
