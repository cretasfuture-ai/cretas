package com.cretas.aims.service.factorydata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Runs preflight reads on the caller's snapshot and shares the export's first failure. */
record FactoryDataPreflightQueries(Connection connection, String operation, FactoryDataExportFailures failures) {

    FactoryDataPreflightQueries(Connection connection) {
        this(connection, null, null);
    }

    <T> T read(FactoryDataExportFailures.Work<T, SQLException> work) throws SQLException {
        return failures == null ? work.run() : failures.read(operation, work);
    }

    <T> T query(String sql, Rows<T> rows) throws SQLException {
        return query(sql, statement -> {}, rows);
    }

    <T> T query(String sql, Binder binder, Rows<T> rows) throws SQLException {
        PreparedStatement statement = read(() -> connection.prepareStatement(sql));
        try (var closeStatement = cleanup(statement::close, "（关闭语句）")) {
            ResultSet result = read(() -> {
                binder.bind(statement);
                return statement.executeQuery();
            });
            try (var closeResult = cleanup(result::close, "（关闭结果集）")) {
                // Record JDBC execution and row-reading failures before either close can race the heartbeat.
                return read(() -> rows.read(result));
            }
        }
    }

    private JdbcCleanup cleanup(JdbcCleanup close, String context) {
        if (failures == null) return close;
        var tracked = failures.cleanup(close, operation + context);
        return tracked::close;
    }

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    interface Rows<T> {
        T read(ResultSet result) throws SQLException;
    }

    @FunctionalInterface
    private interface JdbcCleanup extends AutoCloseable {
        @Override
        void close() throws SQLException;
    }
}
