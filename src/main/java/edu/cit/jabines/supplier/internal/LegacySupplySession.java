package edu.cit.jabines.supplier.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal session management for LegacySupply.
 * Handles authentication and session token lifecycle.
 */
@Slf4j
@Component
public class LegacySupplySession {

    private String sessionToken;
    private LocalDateTime sessionExpiredAt;
    private static final int SESSION_LIFETIME_SECONDS = 3600; // 1 hour

    /**
     * Check if session is valid
     */
    public boolean isValid() {
        if (sessionToken == null) {
            return false;
        }
        if (sessionExpiredAt == null) {
            return false;
        }
        return LocalDateTime.now().isBefore(sessionExpiredAt);
    }

    /**
     * Set a new session token (called after successful authentication)
     */
    public void setToken(String token) {
        this.sessionToken = token;
        this.sessionExpiredAt = LocalDateTime.now().plusSeconds(SESSION_LIFETIME_SECONDS);
        log.debug("Session set, expires at: {}", sessionExpiredAt);
    }

    /**
     * Get current session token
     */
    public String getToken() {
        return sessionToken;
    }

    /**
     * Invalidate current session
     */
    public void invalidate() {
        sessionToken = null;
        sessionExpiredAt = null;
        log.debug("Session invalidated");
    }

    /**
     * Get measured session lifetime (for testing/documentation)
     */
    public int getSessionLifetimeSeconds() {
        return SESSION_LIFETIME_SECONDS;
    }

}
