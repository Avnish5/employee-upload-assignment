package com.example.employees.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Path preloadPath, Path uploadDir, int chunkSize) {
}
