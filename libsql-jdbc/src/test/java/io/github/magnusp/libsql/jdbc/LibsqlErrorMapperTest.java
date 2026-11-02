package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;

import static org.assertj.core.api.Assertions.assertThat;

class LibsqlErrorMapperTest {

    @Test
    void aForbiddenHttpResponseMapsToAnAuthorizationFailure() {
        SQLException mapped = LibsqlErrorMapper.toSQLException(
                new LibsqlException("Authorization forbidden: nope", null, 403));

        assertThat((Throwable) mapped)
                .isInstanceOfSatisfying(SQLInvalidAuthorizationSpecException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Authorization forbidden: nope");
                    assertThat(e.getSQLState()).isEqualTo("28000");
                });
    }
}
