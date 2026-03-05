package com.sms.gateway.message;

import com.sms.gateway.carrier.Carrier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
            select o.carrier as carrier,
               sum(case when o.status = 'SENT' then 1 else 0 end) as successfull,
               sum(case when o.status = 'FAILED' then 1 else 0 end) as failed
            from OutboundMessage o
            where o.date >= :startDate and o.date <= :endDate
            group by o.carrier
            """)
        java.util.List<CarrierStatsRow> summarizeByCarrierInDateRange(
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate
        );

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
                                                where (
                                                                :phonePrimary is null
                                                                or o.phone like concat('%', :phonePrimary, '%')
                                                                or (:phoneAltOne is not null and o.phone like concat('%', :phoneAltOne, '%'))
                                                                or (:phoneAltTwo is not null and o.phone like concat('%', :phoneAltTwo, '%'))
                                                            )
                            and (:carrier is null or o.carrier = :carrier)
                            and (:status is null or upper(o.status) = :status)
                            and (:startDate is null or o.date >= :startDate)
                            and (:endDate is null or o.date <= :endDate)
                        """)
        Page<OutboundMessage> search(
                                                @Param("phonePrimary") String phonePrimary,
                                                @Param("phoneAltOne") String phoneAltOne,
                                                @Param("phoneAltTwo") String phoneAltTwo,
                        @Param("carrier") Carrier carrier,
                        @Param("status") String status,
                        @Param("startDate") java.time.Instant startDate,
                        @Param("endDate") java.time.Instant endDate,
                        Pageable pageable
        );
}
