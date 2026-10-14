package net.mtree.livingplayers.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** One forward-only schema step. Runs inside a transaction together with the version bump. */
public record Migration(int version, String description, Step step) {

    @FunctionalInterface
    public interface Step { void apply(Connection c) throws SQLException; }

    public static Migration ofStatements(int version, String description, String... sql) {
        return new Migration(version, description, c -> {
            try (Statement st = c.createStatement()) {
                for (String s : sql) st.execute(s);
            }
        });
    }
}
