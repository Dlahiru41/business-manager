package com.bmp.web.etag;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.VersionConflict;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * {@code If-Match} handling for updates of versioned resources: missing (or {@code *}, or not a version tag) is 428
 * {@code PRECONDITION_REQUIRED}; a stale version is 412 {@code VERSION_CONFLICT} carrying the current version.
 */
public final class IfMatch {

    private IfMatch() {}

    /**
     * The version the client expects, parsed from the {@code If-Match} header value. Controllers pass it into the
     * command; the handler compares it with the loaded aggregate via {@link #check(long, long)}.
     */
    public static long expectedVersion(@Nullable String ifMatch) {
        OptionalLong version = Etags.parse(ifMatch);
        if (version.isEmpty()) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.PRECONDITION_REQUIRED,
                    "If-Match with the resource version (ETag) is required",
                    Map.of());
        }
        return version.getAsLong();
    }

    /** Reads {@code If-Match} from the request and checks it against the current version. */
    public static void require(HttpServletRequest request, long currentVersion) {
        check(expectedVersion(request.getHeader(HttpHeaders.IF_MATCH)), currentVersion);
    }

    /** Throws {@link VersionConflict} when the expected version is not the current one. */
    public static void check(long expectedVersion, long currentVersion) {
        if (expectedVersion != currentVersion) {
            throw new VersionConflict(currentVersion);
        }
    }
}
