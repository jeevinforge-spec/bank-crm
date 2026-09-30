package com.bank.crm.repository;

import com.bank.crm.model.BulkLoadError;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BulkLoadErrorRepository extends JpaRepository<BulkLoadError, Long> {

    List<BulkLoadError> findByJobIdOrderByRowNumberAsc(String jobId, Pageable pageable);
}
