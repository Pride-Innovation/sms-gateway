package com.sms.gateway.store;

import com.sms.gateway.service.SmsStatus;
import com.sms.gateway.service.SmsStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Primary
public class MySqlSmsStore implements SmsStore {

    private final SmsStatusRepository statusRepo;
    private final SmsMessageIdRepository msgIdRepo;
    private final IdempotencyKeyRepository idemRepo;

    public MySqlSmsStore(SmsStatusRepository statusRepo,
                         SmsMessageIdRepository msgIdRepo,
                         IdempotencyKeyRepository idemRepo) {
        this.statusRepo = statusRepo;
        this.msgIdRepo = msgIdRepo;
        this.idemRepo = idemRepo;
    }

    @Override
    @Transactional
    public void put(SmsStatus status, String idempotencyKeyHash) {
        // Upsert status record
        SmsStatusRecord rec = new SmsStatusRecord();
        rec.setRequestId(status.getRequestId());
        rec.setToMsisdn(status.getToMsisdn());
        rec.setSenderId(status.getSenderId());
        rec.setCreatedAt(status.getCreatedAt());
        rec.setState(status.getState());
        rec.setError(status.getError());
        statusRepo.save(rec);

        // Save idempotency mapping if provided (unique key enforces first writer wins)
        if (idempotencyKeyHash != null) {
            idemRepo.findByKeyHash(idempotencyKeyHash).orElseGet(() -> {
                IdempotencyKeyRecord k = new IdempotencyKeyRecord();
                k.setKeyHash(idempotencyKeyHash);
                k.setRequestId(status.getRequestId());
                k.setCreatedAt(Instant.now());
                return idemRepo.save(k);
            });
        }
    }

    @Override
    public SmsStatus get(String requestId) {
        return statusRepo.findById(requestId).map(rec -> {
            SmsStatus s = new SmsStatus(
                    rec.getRequestId(), rec.getToMsisdn(), rec.getSenderId(), rec.getCreatedAt(), rec.getState(), rec.getError()
            );
            List<SmsMessageIdRecord> mids = msgIdRepo.findByRequestId(requestId);
            for (SmsMessageIdRecord m : mids) {
                s.getMessageIds().add(m.getMessageId());
            }
            return s;
        }).orElse(null);
    }

    @Override
    @Transactional
    public void updateState(String requestId, String state, String error) {
        statusRepo.findById(requestId).ifPresent(rec -> {
            rec.setState(state);
            rec.setError(error);
            statusRepo.save(rec);
        });
    }

    @Override
    @Transactional
    public void addMessageId(String requestId, String messageId) {
        if (messageId == null || messageId.isBlank()) return;
        SmsMessageIdRecord m = new SmsMessageIdRecord();
        m.setRequestId(requestId);
        m.setMessageId(messageId);
        msgIdRepo.save(m);
    }

    @Override
    public String findByIdempotencyKey(String idempotencyKeyHash) {
        if (idempotencyKeyHash == null) return null;
        return idemRepo.findByKeyHash(idempotencyKeyHash).map(IdempotencyKeyRecord::getRequestId).orElse(null);
    }
}
