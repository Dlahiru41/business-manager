package com.bmp.web.request;

/**
 * Request attribute names. Inner filters clear the MDC when they finish, so they also publish what the outermost
 * access log needs as request attributes.
 */
public final class RequestAttributes {

    public static final String REQUEST_ID = "bmp.requestId";
    public static final String TENANT_ID = "bmp.tenantId";
    public static final String USER_ID = "bmp.userId";
    public static final String DEVICE_ID = "bmp.deviceId";
    public static final String IDEMPOTENT_REPLAY = "bmp.idempotentReplay";

    /** Response header naming the request id. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private RequestAttributes() {}
}
