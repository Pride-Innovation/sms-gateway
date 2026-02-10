package com.sms.gateway.carrier;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CarrierPrefixRepository extends JpaRepository<CarrierPrefix, Long> {
    List<CarrierPrefix> findByCarrierOrderByPrefixAsc(Carrier carrier);

    List<CarrierPrefix> findByCarrierAndActiveTrueOrderByPrefixAsc(Carrier carrier);

    Optional<CarrierPrefix> findByCarrierAndPrefix(Carrier carrier, String prefix);

    @Query(
            value = "SELECT carrier FROM carrier_prefixes WHERE active=1 AND ?1 LIKE CONCAT(prefix, '%') ORDER BY LENGTH(prefix) DESC LIMIT 1",
            nativeQuery = true
    )
    String resolveCarrierNative(String normalizedMsisdn);
}
