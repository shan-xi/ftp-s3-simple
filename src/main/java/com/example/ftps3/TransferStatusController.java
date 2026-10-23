package com.example.ftps3;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class TransferStatusController {
    private final JdbcTemplate jdbc;

    public TransferStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/api/transfers")
    public List<Map<String, Object>> transfers() {
        return jdbc.queryForList("SELECT id,source_path,source_size,s3_key,status,retry_count,last_error,created_at,updated_at FROM transfer_job ORDER BY id DESC LIMIT 100");
    }
}
