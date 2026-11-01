package com.example.ftps3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

@Configuration
public class AwsS3Config {
    @Bean(destroyMethod = "close")
    S3AsyncClient s3AsyncClient(@Value("${transfer.s3.region}") String region) {
        return S3AsyncClient.builder()
                .region(Region.of(region))
                .multipartEnabled(true)
                .build();
    }

    @Bean(destroyMethod = "close")
    S3TransferManager s3TransferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder().s3Client(s3AsyncClient).build();
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${transfer.s3.region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }
}
