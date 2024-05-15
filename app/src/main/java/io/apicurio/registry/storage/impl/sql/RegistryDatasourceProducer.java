package io.apicurio.registry.storage.impl.sql;

import io.agroal.api.AgroalDataSource;
import io.apicurio.common.apps.config.Info;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;

import java.sql.SQLException;

public class RegistryDatasourceProducer {

    @Inject
    Logger log;

    @ConfigProperty(name = "apicurio.storage.sql.kind", defaultValue = "h2")
    @Info(category = "storage", description = "Application datasource database type", availableSince = "3.0.0.Final")
    String databaseType;

    @Inject
    @Named("postgresql")
    AgroalDataSource postgresqlDatasource;

    @Inject
    @Named("mssql")
    AgroalDataSource mssqlDatasource;

    @Inject
    @Named("h2")
    AgroalDataSource h2Datasource;

    @Produces
    @ApplicationScoped
    @Named("application")
    public AgroalDataSource produceDatasource() throws SQLException {
        log.debug("Creating an instance of ISqlStatements for DB: " + databaseType);

        final RegistryDatabaseKind databaseKind = RegistryDatabaseKind.valueOf(databaseType);

        AgroalDataSource agroalDataSource;

        switch (databaseKind) {
            case h2:
                agroalDataSource = h2Datasource;
                break;
            case mssql:
                agroalDataSource = mssqlDatasource;
                break;
            case postgresql:
                agroalDataSource = postgresqlDatasource;
                break;
            default:
                throw new IllegalStateException(String.format("Unrecognized database type %s", databaseType));
        }

        return agroalDataSource;
    }
}
