package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClientConfig;

record ParsedConnection(LibsqlClientConfig client, LibsqlConnection.TransactionMode transactionMode) {}
