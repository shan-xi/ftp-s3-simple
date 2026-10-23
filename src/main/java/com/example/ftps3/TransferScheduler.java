package com.example.ftps3;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TransferScheduler {
    private final TransferService service;
    private final TransferJobRepository jobs;

    public TransferScheduler(TransferService service, TransferJobRepository jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${transfer.ftp.scan-delay-ms:30000}")
    public void scan() {
        try {
            int count = jobs.retryFailed(5);
            if (count > 0) System.out.println("Re-queued " + count + " failed job(s)");
            service.scanAndTransfer();
        } catch (Exception e) {
            System.err.println("FTP scan failed: " + e.getMessage());
        }
    }
}
