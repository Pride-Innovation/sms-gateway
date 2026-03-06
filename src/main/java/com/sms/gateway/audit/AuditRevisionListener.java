package com.sms.gateway.audit;

import org.hibernate.envers.RevisionListener;

public class AuditRevisionListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        if (!(revisionEntity instanceof AuditRevisionEntity auditRevision)) {
            return;
        }

        AuditActor actor = AuditActorResolver.resolveCurrentActor();
        auditRevision.setActorType(actor.actorType());
        auditRevision.setActorId(actor.actorId());

        AuditRequestContext requestContext = AuditRequestContextHolder.get();
        if (requestContext == null) {
            return;
        }

        auditRevision.setIpAddress(requestContext.ipAddress());
        auditRevision.setUserAgent(requestContext.userAgent());
        auditRevision.setRequestId(requestContext.requestId());
    }
}
