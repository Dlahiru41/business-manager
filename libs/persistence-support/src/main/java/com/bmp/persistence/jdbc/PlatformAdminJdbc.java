package com.bmp.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one audited code path allowed to work across tenants (06-persistence-and-data.md §4). It connects as the
 * {@code BYPASSRLS} role {@code bmp_platform_admin}, which only has the grants platform jobs need (e.g. purging
 * expired idempotency records). Not a {@code DataSource} bean, so nothing picks it up by accident.
 */
public class PlatformAdminJdbc implements DisposableBean {

    private static final int POOL_SIZE = 2;

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    public PlatformAdminJdbc(String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(POOL_SIZE);
        config.setMinimumIdle(0);
        config.setPoolName("bmp-platform-admin");
        this.dataSource = new HikariDataSource(config);
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Use only from platform jobs and audited administration endpoints. */
    public JdbcClient jdbc() {
        return jdbc;
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
