package com.example.ftps3;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;

@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferJobRepository jobs;
    private final S3TransferManager transferManager;
    private final S3Client s3;
    private final String host, user, password, directory, bucket, prefix;
    private final int port, connectTimeoutMs, dataTimeoutMs;
    private final Path tempDir;

    public TransferService(
            TransferJobRepository jobs,
            S3TransferManager transferManager,
            S3Client s3,
            @Value("${transfer.ftp.host}") String host,
            @Value("${transfer.ftp.port}") int port,
            @Value("${transfer.ftp.username}") String user,
            @Value("${transfer.ftp.password}") String password,
            @Value("${transfer.ftp.directory}") String directory,
            @Value("${transfer.s3.bucket}") String bucket,
            @Value("${transfer.s3.prefix}") String prefix,
            @Value("${transfer.s3.temp-directory}") String tempDir,
            @Value("${transfer.ftp.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${transfer.ftp.data-timeout-ms:120000}") int dataTimeoutMs) {
        this.jobs = jobs;
        this.transferManager = transferManager;
        this.s3 = s3;
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.directory = directory;
        this.bucket = bucket;
        this.prefix = prefix;
        this.tempDir = Path.of(tempDir);
        this.connectTimeoutMs = connectTimeoutMs;
        this.dataTimeoutMs = dataTimeoutMs;
    }

    private static String joinRemotePath(String directory, String filename) {
        return (directory == null ? "" : directory).replaceAll("/+$", "") + "/" + filename;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public void scanAndTransfer() throws IOException {
        if (bucket == null || bucket.isBlank() || bucket.equals("replace-with-your-bucket")) {
            throw new IllegalStateException("Set S3_BUCKET to your real S3 bucket name first.");
        }

        Files.createDirectories(tempDir);
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(connectTimeoutMs);
        ftp.setDefaultTimeout(connectTimeoutMs);
        ftp.setDataTimeout(dataTimeoutMs);

        try {
            ftp.connect(host, port);
            if (!ftp.login(user, password)) throw new IOException("FTP login failed: " + ftp.getReplyString());
            if (!ftp.changeWorkingDirectory(directory)) {
                throw new IOException("FTP directory is unavailable: " + directory + "; " + ftp.getReplyString());
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            FTPFile[] files = ftp.listFiles();
            for (FTPFile file : files) {
                if (!file.isFile()) continue;
                String name = file.getName();
                if (name.endsWith(".done") || name.endsWith(".part")) continue;

                String remotePath = joinRemotePath(directory, name);
                // A .done marker is required, and a .part marker always wins if both exist.
                if (remoteExists(ftp, remotePath + ".part")) {
                    log.debug("Skipping {} because its .part marker exists", remotePath);
                    continue;
                }
                if (!remoteExists(ftp, remotePath + ".done")) continue;

                LocalDateTime modifiedAt = file.getTimestamp() == null
                        ? LocalDateTime.of(1970, 1, 1, 0, 0)
                        : LocalDateTime.ofInstant(file.getTimestamp().toInstant(), ZoneId.of("UTC"));
                String versionId = sha256(remotePath + "\n" + file.getSize() + "\n" + modifiedAt);
                String s3Key = buildS3Key(name, versionId);

                var jobId = jobs.claim(remotePath, file.getSize(), modifiedAt, s3Key);
                if (jobId.isEmpty()) {
                    log.debug("Skipping already claimed/completed source version {}", remotePath);
                    continue;
                }

                Path localFile = null;
                try {
                    localFile = Files.createTempFile(tempDir, "ftp-transfer-", ".tmp");
                    downloadToTempFile(ftp, remotePath, localFile, file.getSize());
                    String checksum = sha256(localFile);
                    long localSize = Files.size(localFile);

                    // If S3 succeeded but PostgreSQL did not record SUCCESS before a crash,
                    // matching metadata lets the next attempt reconcile without uploading again.
                    if (!existingObjectMatches(s3Key, checksum, localSize)) {
                        transferManager.uploadFile(UploadFileRequest.builder()
                                .putObjectRequest(b -> b.bucket(bucket)
                                        .key(s3Key)
                                        .metadata(Map.of("source-sha256", checksum,
                                                "source-size", Long.toString(localSize))))
                                .source(localFile)
                                .build()).completionFuture().join();
                    }

                    jobs.success(jobId.get(), checksum);
                    log.info("Transferred {} to s3://{}/{} ({} bytes, sha256={})",
                            remotePath, bucket, s3Key, localSize, checksum);
                } catch (Exception e) {
                    jobs.failed(jobId.get(), rootMessage(e));
                    log.error("Transfer failed for {} (job {}): {}", remotePath, jobId.get(), rootMessage(e), e);
                } finally {
                    if (localFile != null) {
                        try {
                            Files.deleteIfExists(localFile);
                        } catch (IOException cleanupError) {
                            log.warn("Could not remove temporary file {}: {}", localFile, cleanupError.getMessage());
                        }
                    }
                }
            }
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.logout();
                } catch (IOException e) {
                    log.debug("FTP logout failed: {}", e.getMessage());
                }
                try {
                    ftp.disconnect();
                } catch (IOException e) {
                    log.debug("FTP disconnect failed: {}", e.getMessage());
                }
            }
        }
    }

    private void downloadToTempFile(FTPClient ftp, String remotePath, Path localFile, long expectedSize) throws IOException {
        // Re-check the producer's completion protocol immediately before downloading.
        if (remoteExists(ftp, remotePath + ".part") || !remoteExists(ftp, remotePath + ".done")) {
            throw new IOException("Completion marker changed before download: " + remotePath);
        }
        try (OutputStream output = Files.newOutputStream(localFile)) {
            if (!ftp.retrieveFile(remotePath, output)) {
                throw new IOException("FTP download failed: " + ftp.getReplyString());
            }
        }
        long actualSize = Files.size(localFile);
        if (actualSize != expectedSize) {
            throw new IOException("Downloaded size mismatch for " + remotePath + ": expected " + expectedSize + " bytes but received " + actualSize);
        }
        // Ensure the marker still exists after the download, so a producer that violates
        // the marker protocol does not silently pass a partial/changed file downstream.
        if (remoteExists(ftp, remotePath + ".part") || !remoteExists(ftp, remotePath + ".done")) {
            throw new IOException("Completion marker changed during download: " + remotePath);
        }
    }

    private boolean existingObjectMatches(String key, String checksum, long size) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Long.toString(size).equals(head.metadata().get("source-size"))
                    && checksum.equals(head.metadata().get("source-sha256"));
        } catch (SdkException e) {
            // Missing object and temporary S3 errors both fall through to an upload attempt;
            // the upload will produce the useful failure if the service is unavailable.
            return false;
        }
    }

    private boolean remoteExists(FTPClient ftp, String path) throws IOException {
        FTPFile[] matches = ftp.listFiles(path);
        return matches != null && matches.length > 0;
    }

    private String buildS3Key(String filename, String versionId) {
        String cleanPrefix = prefix == null ? "" : prefix.replaceAll("^/+|/+$", "");
        String safeName = filename.replace("\\", "_").replace("/", "_");
        String key = (cleanPrefix.isBlank() ? "" : cleanPrefix + "/") + versionId + "/" + safeName;
        return key.replaceAll("^/+", "");
    }
}
