package com.bmp.sales.application;

import com.bmp.iam.api.IamQueries;
import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.Mode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.UserId;
import com.bmp.web.security.AccessPolicy;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Manager approvals at the till (SAL-03, SAL-05, SAL-07, SAL-09, SAL-10): a permission is granted when the caller holds
 * it, or when the named approver does. Online an approver without it is refused ({@code APPROVER_NOT_ALLOWED});
 * offline (a device uploads) only a named approver counts, and one without it is ignored, so the bill is flagged.
 */
@Component
public class Approvals {

    private final AccessPolicy access;
    private final IamQueries iam;

    public Approvals(AccessPolicy access, IamQueries iam) {
        this.access = access;
        this.iam = iam;
    }

    public boolean approved(String permission, @Nullable UUID approver, Mode mode) {
        if (approver != null) {
            if (iam.userHasPermission(UserId.of(approver), permission)) {
                return true;
            }
            if (mode.online()) {
                throw new BusinessRuleViolation(SalesErrorCode.APPROVER_NOT_ALLOWED, Map.of("permission", permission));
            }
            return false;
        }
        return mode.online() && access.isGranted(permission);
    }
}
