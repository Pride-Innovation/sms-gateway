package com.sms.gateway.admin.dto;

public record DashboardClientTotalsResponse(
        Long clientId,
        String clientName,
        CarrierTotals mtn,
        CarrierTotals airtel
) {
    public record CarrierTotals(long failed, long successfull) {
    }
}
