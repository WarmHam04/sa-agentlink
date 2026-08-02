package io.agentlink.infrastructure;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcPersistenceConfiguration {
    @Bean
    public DataSource agentLinkDataSource(
            @Value("${agentlink.persistence.jdbc.url}") String url,
            @Value("${agentlink.persistence.jdbc.username}") String username,
            @Value("${agentlink.persistence.jdbc.password}") String password
    ) {
        return new DriverManagerDataSource(url, username, password);
    }

    @Bean(initMethod = "migrate")
    public Flyway agentLinkFlyway(DataSource agentLinkDataSource) {
        return Flyway.configure()
                .dataSource(agentLinkDataSource)
                .locations("classpath:db/migration")
                .load();
    }

    @Bean
    public JdbcTemplate agentLinkJdbcTemplate(DataSource agentLinkDataSource) {
        return new JdbcTemplate(agentLinkDataSource);
    }

    @Bean
    public PlatformTransactionManager agentLinkTransactionManager(DataSource agentLinkDataSource) {
        return new DataSourceTransactionManager(agentLinkDataSource);
    }
}
