package io.agentlink.application;

public class TenantRegionMismatchException extends RuntimeException {
    private final String homeRegion;

    public TenantRegionMismatchException(String tenantId, String homeRegion, String currentRegion) {
        super("tenant " + tenantId + " is writable only in region " + homeRegion
                + "; current region is " + currentRegion);
        this.homeRegion = homeRegion;
    }

    public String homeRegion() {
        return homeRegion;
    }
}
