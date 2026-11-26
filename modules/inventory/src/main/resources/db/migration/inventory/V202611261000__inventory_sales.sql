-- T03-04: returns and voids find the movements of a bill line (ADR-026).
CREATE INDEX ix_stock_movement_tenant_source_line ON inventory.stock_movement (tenant_id, source_line_id)
    WHERE source_line_id IS NOT NULL;
