package com.sms.gateway.admin.dto;

public class SetBlockedRequest {

    private boolean blocked;

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }
}
