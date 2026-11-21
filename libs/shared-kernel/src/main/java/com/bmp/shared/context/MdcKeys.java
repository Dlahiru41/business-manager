package com.bmp.shared.context;

/** Names of the logging context (MDC) keys shared by every module (05-logging-and-observability.md §2). */
public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String REQUEST_ID = "requestId";
    public static final String TENANT_ID = "tenantId";
    public static final String USER_ID = "userId";
    public static final String DEVICE_ID = "deviceId";
    public static final String MODULE = "module";
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";

    private MdcKeys() {}
}
