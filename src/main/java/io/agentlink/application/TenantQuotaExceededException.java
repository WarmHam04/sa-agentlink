package io.agentlink.application;

public class TenantQuotaExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public TenantQuotaExceededException(String tenantId, String quota) {
        this(tenantId, quota, 60L);
    }

    public TenantQuotaExceededException(String tenantId, String quota, long retryAfterSeconds) {
        super("tenant quota exceeded: " + tenantId + " / " + quota);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
