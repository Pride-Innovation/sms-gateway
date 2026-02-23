package com.sms.gateway.admin.dto;

public record DashboardCarrierTotalsResponse(
        CarrierTotals mtn,
        CarrierTotals airtel
) {
    public record CarrierTotals(long successfull, long failed, long total) {
    }
}
