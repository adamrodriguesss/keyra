package com.rodrigues.keyra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class DatabaseOwnershipTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void keyraOwnsItsDatabaseAndSchema() {
        assertThat(jdbc.queryForObject(
                        "select pg_get_userbyid(datdba) = current_user from pg_database where datname = current_database()",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select pg_get_userbyid(nspowner) = current_user from pg_namespace where nspname = current_schema()",
                        Boolean.class))
                .isTrue();
    }

    @Test
    void otherRolesCannotConnectToOrCreateInKeyra() {
        String role = "ownership_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create role " + role + " login");
        try {
            assertThat(jdbc.queryForObject(
                            "select has_database_privilege(?, current_database(), 'CONNECT')", Boolean.class, role))
                    .isFalse();
            assertThat(jdbc.queryForObject(
                            "select has_schema_privilege(?, current_schema(), 'CREATE')", Boolean.class, role))
                    .isFalse();
        } finally {
            jdbc.execute("drop role " + role);
        }
    }
}
