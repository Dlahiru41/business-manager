package com.bmp.audit.api;

/**
 * Appends entries to the audit trail of the tenant bound to the current context. Joins the caller's transaction (so
 * the entry commits or rolls back with the change it describes) or starts one.
 */
public interface AuditWriter {

    void record(AuditEntry entry);
}
