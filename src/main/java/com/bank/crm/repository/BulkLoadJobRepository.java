package com.bank.crm.repository;

import com.bank.crm.model.BulkLoadJob;
import com.bank.crm.model.JobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BulkLoadJobRepository extends JpaRepository<BulkLoadJob, Long> {

    Optional<BulkLoadJob> findByJobId(String jobId);

    List<BulkLoadJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<BulkLoadJob> findByStatusIn(Collection<JobStatus> statuses);
}
