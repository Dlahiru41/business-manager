package com.bmp.party.application;

import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.web.security.AccessPolicy;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Permission checks inside party use cases: a caller with only {@code party:create-customer} may add customers, but
 * suppliers and duplicate phones (PTY-02) need {@code party:write}.
 */
@Component
public class PartyAccess {

    static final String WRITE = "party:write";

    private final AccessPolicy access;

    public PartyAccess(AccessPolicy access) {
        this.access = access;
    }

    public void requireWrite(String reason) {
        if (!access.isGranted(WRITE)) {
            throw new BusinessRuleViolation(
                    PartyErrorCode.PARTY_PERMISSION_REQUIRED,
                    "This change needs " + WRITE,
                    Map.of("permission", WRITE, "reason", reason));
        }
    }
}
