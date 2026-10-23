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

    public Optional<Long> createIfMissing(String path, long size, LocalDateTime modifiedAt, String s3Key) {
        var rows = jdbc.query("INSERT INTO transfer_job(source_path,source_size,source_modified_at,s3_key,status) VALUES(?,?,?,?, 'PROCESSING') ON CONFLICT (source_path,source_size,source_modified_at) DO UPDATE SET status='PROCESSING', updated_at=CURRENT_TIMESTAMP WHERE transfer_job.status='PENDING' RETURNING id",
                (rs, n) -> rs.getLong("id"), path, size, Timestamp.valueOf(modifiedAt), s3Key);
        return rows.stream().findFirst();
    }

    public void success(long id) {
        jdbc.update("UPDATE transfer_job SET status='SUCCESS',last_error=NULL,updated_at=? WHERE id=?",
                Timestamp.from(Instant.now()), id);
    }

    public void failed(long id, String error) {
        String e = error == null ? "Unknown error" : error;
        if (e.length() > 4000) e = e.substring(0, 4000);
        jdbc.update("UPDATE transfer_job SET status='FAILED',retry_count=retry_count+1,last_error=?,updated_at=? WHERE id=?",
                e, Timestamp.from(Instant.now()), id);
    }

    public int retryFailed(int max) {
        return jdbc.update("UPDATE transfer_job SET status='PENDING',updated_at=? WHERE status='FAILED' AND retry_count < ?",
                Timestamp.from(Instant.now()), max);
    }
}
