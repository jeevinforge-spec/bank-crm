package com.bank.crm.config;

import com.bank.crm.service.bulk.BulkLoadProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /**
     * Runs bulk-job coordinators (one thread per running job). Its size caps how many jobs load
     * concurrently; extra jobs wait in the queue with status QUEUED. Each running job then gets
     * its own worker pool sized by the thread count the user chose for that job.
     */
    @Bean(name = "bulkJobCoordinator")
    public TaskExecutor bulkJobCoordinator(BulkLoadProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.maxConcurrentJobs());
        executor.setMaxPoolSize(props.maxConcurrentJobs());
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("bulk-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
