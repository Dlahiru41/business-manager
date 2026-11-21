package com.bmp.persistence.tenant;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.id.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Starts every transaction with the tenant's id in the transaction-local setting {@code app.tenant_id}, which every
 * row-level security policy reads (06-persistence-and-data.md §4). JPA and {@code JdbcClient} code share the
 * connection, so both are covered. Without a tenant nothing is set and the policies fail closed with
 * {@code TENANT_CONTEXT_MISSING}. The tenant is fixed for the whole transaction: switching tenants needs a new one.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    private static final long serialVersionUID = 1L;
    private static final String SET_TENANT = "SELECT set_config('" + TenantSql.TENANT_SETTING + "', ?, true)";

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        TenantContext.current().ifPresent(this::applyTenant);
    }

    private void applyTenant(TenantId tenant) {
        DataSource dataSource = getDataSource();
        Object resource = dataSource != null ? TransactionSynchronizationManager.getResource(dataSource) : null;
        if (!(resource instanceof ConnectionHolder holder)) {
            throw new TechnicalException(
                    PlatformErrorCode.INTERNAL_ERROR, Map.of("reason", "JDBC connection not exposed to JPA"), null);
        }
        Connection connection = holder.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenant.value().toString());
            statement.execute();
        } catch (SQLException e) {
            throw new TechnicalException(PlatformErrorCode.INTERNAL_ERROR, Map.of("reason", "set tenant failed"), e);
        }
    }
}
