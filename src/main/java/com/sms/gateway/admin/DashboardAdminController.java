package com.sms.gateway.admin;

import com.sms.gateway.admin.dto.DashboardCarrierTotalsResponse;
import com.sms.gateway.admin.dto.DashboardClientTotalsResponse;
import com.sms.gateway.carrier.Carrier;
import com.sms.gateway.message.OutboundMessageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardAdminController {

    private final OutboundMessageRepository outboundMessageRepository;

    public DashboardAdminController(OutboundMessageRepository outboundMessageRepository) {
        this.outboundMessageRepository = outboundMessageRepository;
    }

    @GetMapping("/carrier-totals")
    public DashboardCarrierTotalsResponse carrierTotals() {
        Map<Carrier, Stats> statsByCarrier = new LinkedHashMap<>();
        statsByCarrier.put(Carrier.MTN, new Stats());
        statsByCarrier.put(Carrier.AIRTEL, new Stats());

        for (OutboundMessageRepository.CarrierStatsRow row : outboundMessageRepository.summarizeByCarrier()) {
            Stats s = statsByCarrier.computeIfAbsent(row.getCarrier(), key -> new Stats());
            s.successfull = defaultZero(row.getSuccessfull());
            s.failed = defaultZero(row.getFailed());
        }

        Stats mtn = statsByCarrier.get(Carrier.MTN);
        Stats airtel = statsByCarrier.get(Carrier.AIRTEL);

        return new DashboardCarrierTotalsResponse(
                new DashboardCarrierTotalsResponse.CarrierTotals(mtn.successfull, mtn.failed, mtn.successfull + mtn.failed),
                new DashboardCarrierTotalsResponse.CarrierTotals(airtel.successfull, airtel.failed, airtel.successfull + airtel.failed)
        );
    }

    @GetMapping("/client-totals")
    public List<DashboardClientTotalsResponse> clientTotals() {
        Map<Long, ClientStats> byClient = new LinkedHashMap<>();

        for (OutboundMessageRepository.ClientCarrierStatsRow row : outboundMessageRepository.summarizeByClientAndCarrier()) {
            ClientStats clientStats = byClient.computeIfAbsent(row.getClientId(), id -> new ClientStats(row.getClientId(), row.getClientName()));
            if (row.getCarrier() == Carrier.MTN) {
                clientStats.mtnFailed = defaultZero(row.getFailed());
                clientStats.mtnSuccessfull = defaultZero(row.getSuccessfull());
            } else if (row.getCarrier() == Carrier.AIRTEL) {
                clientStats.airtelFailed = defaultZero(row.getFailed());
                clientStats.airtelSuccessfull = defaultZero(row.getSuccessfull());
            }
        }

        List<DashboardClientTotalsResponse> out = new ArrayList<>();
        for (ClientStats c : byClient.values()) {
            out.add(new DashboardClientTotalsResponse(
                    c.clientId,
                    c.clientName,
                    new DashboardClientTotalsResponse.CarrierTotals(c.mtnFailed, c.mtnSuccessfull),
                    new DashboardClientTotalsResponse.CarrierTotals(c.airtelFailed, c.airtelSuccessfull)
            ));
        }
        return out;
    }

    private long defaultZero(Long v) {
        return v == null ? 0L : v;
    }

    private static class Stats {
        long successfull;
        long failed;
    }

    private static class ClientStats {
        final Long clientId;
        final String clientName;
        long mtnFailed;
        long mtnSuccessfull;
        long airtelFailed;
        long airtelSuccessfull;

        ClientStats(Long clientId, String clientName) {
            this.clientId = clientId;
            this.clientName = clientName;
        }
    }
}
