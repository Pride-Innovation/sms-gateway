package com.sms.gateway.message;

import com.sms.gateway.carrier.Carrier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {
    interface CarrierStatsRow {
        Carrier getCarrier();
        Long getSuccessfull();
        Long getFailed();
    }

    interface ClientCarrierStatsRow {
        Long getClientId();
        String getClientName();
        Carrier getCarrier();
        Long getSuccessfull();
        Long getFailed();
    }

    Optional<OutboundMessage> findByRequestId(String requestId);

    @Query("""
            select o.carrier as carrier,
                   sum(case when o.status = 'SENT' then 1 else 0 end) as successfull,
                   sum(case when o.status = 'FAILED' then 1 else 0 end) as failed
            from OutboundMessage o
            group by o.carrier
            """)
    java.util.List<CarrierStatsRow> summarizeByCarrier();

    @Query("""
            select o.apiClientId as clientId,
                   o.apiClientName as clientName,
                   o.carrier as carrier,
                   sum(case when o.status = 'SENT' then 1 else 0 end) as successfull,
                   sum(case when o.status = 'FAILED' then 1 else 0 end) as failed
            from OutboundMessage o
            where o.apiClientId is not null
            group by o.apiClientId, o.apiClientName, o.carrier
            order by o.apiClientId asc
            """)
    java.util.List<ClientCarrierStatsRow> summarizeByClientAndCarrier();

        @Query("""
                        select o from OutboundMessage o
                        where (:phone is null or o.phone = :phone)
                            and (:carrier is null or o.carrier = :carrier)
                            and (:fromTs is null or o.date >= :fromTs)
                            and (:toTs is null or o.date <= :toTs)
                        """)
        Page<OutboundMessage> search(
                        @Param("phone") String phone,
                        @Param("carrier") Carrier carrier,
                        @Param("fromTs") java.time.Instant fromTs,
                        @Param("toTs") java.time.Instant toTs,
                        Pageable pageable
        );
}
