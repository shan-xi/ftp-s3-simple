package com.example.ftps3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TransferScheduler {
    private static final Logger log = LoggerFactory.getLogger(TransferScheduler.class);

    private final TransferService service;
    private final TransferJobRepository jobs;
    private final int maxRetries;
    private final int staleProcessingMinutes;

    public TransferScheduler(
            TransferService service,
            TransferJobRepository jobs,
            @Value("${transfer.retry.max-retries:5}") int maxRetries,
            @Value("${transfer.retry.stale-processing-minutes:60}") int staleProcessingMinutes) {
        this.service = service;
        this.jobs = jobs;
        this.maxRetries = maxRetries;
        this.staleProcessingMinutes = staleProcessingMinutes;
    }

    @Scheduled(fixedDelayString = "${transfer.ftp.scan-delay-ms:3000}")
    public void scan() {
        try {
            int recovered = jobs.recoverStaleProcessing(staleProcessingMinutes);
            if (recovered > 0) log.warn("Recovered {} stale PROCESSING job(s)", recovered);

            int requeued = jobs.requeueRetryableFailures(maxRetries);
            if (requeued > 0) log.info("Re-queued {} failed job(s) whose retry delay elapsed", requeued);

            service.scanAndTransfer();
        } catch (Exception e) {
            log.error("FTP scan failed: {}", e.getMessage(), e);
        }
    }
}
