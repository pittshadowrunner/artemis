package com.artemis.wms.tenancy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(DataSourceConfig.DbProperties.class)
public class DataSourceConfig {

    @ConfigurationProperties(prefix = "artemis.db")
    public record DbProperties(String url, String user, String password, Integer poolSize) {}

    @Bean(destroyMethod = "close")
    public HikariDataSource hikari(DbProperties db) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(db.url());
        cfg.setUsername(db.user());
        cfg.setPassword(db.password());
        cfg.setMaximumPoolSize(db.poolSize() == null ? 10 : db.poolSize());
        cfg.setPoolName("artemis");
        return new HikariDataSource(cfg);
    }

    @Bean @Primary
    public DataSource dataSource(HikariDataSource hikari) { return new TenantAwareDataSource(hikari); }
}
