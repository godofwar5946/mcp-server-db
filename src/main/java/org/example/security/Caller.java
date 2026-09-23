package org.example.security;

public record Caller(String subject, String sessionId) {
    public String owner() { return subject + ":" + sessionId; }
}
