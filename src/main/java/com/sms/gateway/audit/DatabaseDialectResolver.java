
package com.sms.gateway.audit;

import lombok.Getter;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Getter
@Component
public class DatabaseDialectResolver {

    private final boolean sqlServer;

    public DatabaseDialectResolver(DataSource dataSource) {
        this.sqlServer = detectSqlServer(dataSource);
    }

    private boolean detectSqlServer(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("sql server");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to detect database type", e);
        }
    }

}
