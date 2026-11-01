package com.example.ftps3;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class TransferJobRepository {
    private final JdbcTemplate jdbc;

    public TransferJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically creates a job or claims an existing PENDING job.
     * A unique source-version constraint plus this conditional UPDATE prevents
     * two scans from claiming the same source version at the same time.
     */
    public Optional<Long> claim(String path, long size, LocalDateTime modifiedAt, String s3Key) {
        var rows = jdbc.query("""
                INSERT INTO transfer_job
                    (source_path, source_size, source_modified_at, s3_key, status, updated_at)
                VALUES (?, ?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                ON CONFLICT (source_path, source_size, source_modified_at)
                DO UPDATE SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
                WHERE transfer_job.status = 'PENDING'
                RETURNING id
                """, (rs, n) -> rs.getLong("id"), path, size, Timestamp.valueOf(modifiedAt), s3Key);
        return rows.stream().findFirst();
    }

    public void success(long id, String checksum) {
        jdbc.update("""
                UPDATE transfer_job
                   SET status = 'SUCCESS', checksum_sha256 = ?, last_error = NULL,
                       next_attempt_at = NULL, updated_at = ?
                 WHERE id = ?
                """, checksum, Timestamp.from(Instant.now()), id);
    }

    public void failed(long id, String error) {
        String message = error == null ? "Unknown error" : error;
        if (message.length() > 4000) message = message.substring(0, 4000);
        jdbc.update("""
                UPDATE transfer_job
                   SET status = 'FAILED', retry_count = retry_count + 1,
                       last_error = ?,
                       next_attempt_at = CURRENT_TIMESTAMP +
                           (LEAST(3600, CAST(POWER(2, LEAST(retry_count, 12)) AS INTEGER)) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, message, Timestamp.from(Instant.now()), id);
    }

    /**
     * Re-queue only failed jobs whose exponential-backoff delay has elapsed.
     */
    public int requeueRetryableFailures(int maxRetries) {
        return jdbc.update("""
                UPDATE transfer_job
                   SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                 WHERE status = 'FAILED'
                   AND retry_count < ?
                   AND next_attempt_at <= CURRENT_TIMESTAMP
                """, maxRetries);
    }

    /**
     * Recover PROCESSING rows left behind by a process/container crash.
     */
    public int recoverStaleProcessing(int staleMinutes) {
        return jdbc.update("""
                UPDATE transfer_job
                   SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP,
                       last_error = COALESCE(last_error || E'\\n', '') || 'Recovered stale PROCESSING job after restart/timeout.'
                 WHERE status = 'PROCESSING'
                   AND updated_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 minute')
                """, staleMinutes);
    }
}
