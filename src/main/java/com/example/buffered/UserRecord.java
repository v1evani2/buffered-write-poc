package com.example.buffered;

public record UserRecord(
        long userId,
        String username,
        String password,
        String lockStatus,
        int failedCount
) {
    public boolean isLocked() {
        return "LOCKED".equalsIgnoreCase(lockStatus);
    }
}

