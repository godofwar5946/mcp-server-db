package org.example.db.service.dto;

public record QueryColumn(String key, String label, int jdbcType, String databaseType) {}
