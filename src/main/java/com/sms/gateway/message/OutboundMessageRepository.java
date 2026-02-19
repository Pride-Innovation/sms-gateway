package com.sms.gateway.message;

import com.sms.gateway.carrier.Carrier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {
    Optional<OutboundMessage> findByRequestId(String requestId);

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
