package com.example.ftps3;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class TransferService {
    private final TransferJobRepository jobs;
    private final String host, user, password, directory, bucket, prefix;
    private final int port;
    private final Path tempDir;
    private final Region region;

    public TransferService(
            TransferJobRepository jobs,
            @Value("${transfer.ftp.host}") String host,
            @Value("${transfer.ftp.port}") int port,
            @Value("${transfer.ftp.username}") String user,
            @Value("${transfer.ftp.password}") String password,
            @Value("${transfer.ftp.directory}") String directory,
            @Value("${transfer.s3.bucket}") String bucket,
            @Value("${transfer.s3.prefix}") String prefix,
            @Value("${transfer.s3.region}") String region,
            @Value("${transfer.s3.temp-directory}") String tempDir) {
        this.jobs = jobs;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.directory = directory;
        this.bucket = bucket;
        this.prefix = prefix;
        this.region = Region.of(region);
        this.tempDir = Path.of(tempDir);
    }

    public void scanAndTransfer() throws IOException {
        if (bucket.equals("replace-with-your-bucket"))
            throw new IllegalStateException("Set S3_BUCKET to your real S3 bucket name first.");
        Files.createDirectories(tempDir);
        FTPClient ftp = new FTPClient();
        try {
            ftp.connect(host, port);
            if (!ftp.login(user, password)) throw new IOException("FTP login failed: " + ftp.getReplyString());
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            for (var f : ftp.listFiles(directory)) {
                if (!f.isFile() || f.getName().endsWith(".done") || f.getName().endsWith(".part")) continue;
                String path = directory.replaceAll("/+$", "") + "/" + f.getName();
                if (ftp.listFiles(path + ".done").length == 0) continue;
                LocalDateTime modified = f.getTimestamp() == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : LocalDateTime.ofInstant(f.getTimestamp().toInstant(), ZoneId.systemDefault());
                String key = (prefix.replaceAll("/+$", "") + "/" + f.getName()).replaceAll("^/", "");
                var id = jobs.createIfMissing(path, f.getSize(), modified, key);
                if (id.isEmpty()) continue;
                Path local = tempDir.resolve(UUID.randomUUID() + "-" + f.getName());
                try {
                    try (var out = Files.newOutputStream(local)) {
                        if (!ftp.retrieveFile(path, out))
                            throw new IOException("FTP download failed: " + ftp.getReplyString());
                    }
                    try (S3TransferManager manager =
                                 S3TransferManager.builder()
                                         .s3Client(S3AsyncClient.builder()
                                                 .region(region)
                                                 .multipartEnabled(true)
                                                 .build())
                                         .build()) {
                        manager.uploadFile(
                                UploadFileRequest.builder()
                                        .putObjectRequest(b -> b.bucket(bucket).key(key))
                                        .source(local)
                                        .build())
                                .completionFuture().join();
                    }
                    jobs.success(id.get());
                } catch (Exception e) {
                    jobs.failed(id.get(), e.getMessage());
                    System.err.println("Transfer failed for " + path + ": " + e.getMessage());
                } finally {
                    Files.deleteIfExists(local);
                }
            }
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.logout();
                } finally {
                    ftp.disconnect();
                }
            }
        }
    }
}
