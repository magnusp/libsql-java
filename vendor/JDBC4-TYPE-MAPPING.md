# TABLE B-1 JDBC Types Mapped to Java Types

This table shows the conceptual correspondence between JDBC types and Java types.
A programmer should write code with this mapping in mind. For example, if a
value in the database is a SMALLINT, a short should be the data type used in a
JDBC application.
All CallableStatement getter methods except for getObject use this mapping. The
getObject methods for both the CallableStatement and ResultSet interfaces use the
mapping in TABLE B-3.

| JDBC Type     | Java Type                  |
|---------------|----------------------------|
| CHAR          | String                     |
| VARCHAR       | String                     |
| LONGVARCHAR   | String                     |
| NUMERIC       | java.math.BigDecimal       |
| DECIMAL       | java.math.BigDecimal       |
| BIT           | boolean                    |
| BOOLEAN       | boolean                    |
| TINYINT       | byte                       |
| SMALLINT      | short                      |
| INTEGER       | int                        |
| BIGINT        | long                       |
| REAL          | float                      |
| FLOAT         | double                     |
| DOUBLE        | double                     |
| BINARY        | byte[]                     |
| VARBINARY     | byte[]                     |
| LONGVARBINARY | byte[]                     |
| DATE          | java.sql.Date              |
| TIME          | java.sql.Time              |
| TIMESTAMP     | java.sql.Timestamp         |
| CLOB          | java.sql.Clob              |
| BLOB          | java.sql.Blob              |
| ARRAY         | java.sql.Array             |
| DISTINCT      | mapping of underlying type |
| STRUCT        | java.sql.Struct            |
| REF           | java.sql.Ref               |
| DATALINK      | java.net.URL               |
| JAVA_OBJECT   | underlying Java class      |
| ROWID         | java.sql.RowId             |
| NCHAR         | String                     |
| NVARCHAR      | String                     |
| LONGNVARCHAR  | String                     |
| NCLOB         | java.sql.NClob             |
| SQLXML        | java.sql.SQLXML            |

# TABLE B-2 Java Types Mapper to JDBC Types

This table shows the mapping a driver should use for the updater methods in the
ResultSet interface and for IN parameters. PreparedStatement setter methods and
RowSet setter methods use this table for mapping an IN parameter, which is a Java
type, to the JDBC type that will be sent to the database. Note that the setObject
methods for these two interfaces use the mapping shown in TABLE B-4.

| Java Type            | JDBC Type                                                   |
|----------------------|-------------------------------------------------------------|
| String               | CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR or LONGNVARCHAR |
| java.math.BigDecimal | NUMERIC                                                     |
| boolean              | BIT or BOOLEAN                                              |
| byte                 | TINYINT                                                     |
| short                | SMALLINT                                                    |
| int                  | INTEGER                                                     |
| long                 | BIGINT                                                      |
| float                | REAL                                                        |
| double               | DOUBLE                                                      |
| byte[]               | BINARY, VARBINARY, or LONGVARBINARY                         |
| java.sql.Date        | DATE                                                        |
| java.sql.Time        | TIME                                                        |
| java.sql.Timestamp   | TIMESTAMP                                                   |
| java.sql.Clob        | CLOB                                                        |
| java.sql.Blob        | BLOB                                                        |
| java.sql.Array       | ARRAY                                                       |
| java.sql.Struct      | STRUCT                                                      |
| java.sql.Ref         | REF                                                         |
| java.net.URL         | DATALINK                                                    |
| Java class           | JAVA_OBJECT                                                 |
| java.sql.RowId       | ROWID                                                       |
| java.sql.NClob       | NCLOB                                                       |
| java.sql.SQLXML      | SQLXML                                                      |

# TABLE B-3 JDBC Types Mapped to Java Object Types

ResultSet.getObject and CallableStatement.getObject use the mapping shown in this
table for standard mappings.

| JDBC Type     | Java Object Type                    |
|---------------|-------------------------------------|
| CHAR          | String                              |
| VARCHAR       | String                              |
| LONGVARCHAR   | String                              |
| NUMERIC       | java.math.BigDecimal                |
| DECIMAL       | java.math.BigDecimal                |
| BIT           | Boolean                             |
| BOOLEAN       | Boolean                             |
| TINYINT       | Integer                             |
| SMALLINT      | Integer                             |
| INTEGER       | Integer                             |
| BIGINT        | Long                                |
| REAL          | Float                               |
| FLOAT         | Double                              |
| DOUBLE        | Double                              |
| BINARY        | byte[]                              |
| VARBINARY     | byte[]                              |
| LONGVARBINARY | byte[]                              |
| DATE          | java.sql.Date                       |
| TIME          | java.sql.Time                       |
| TIMESTAMP     | java.sql.Timestamp                  |
| DISTINCT      | Object type of underlying type      |
| CLOB          | java.sql.Clob                       |
| BLOB          | java.sql.Blob                       |
| ARRAY         | java.sql.Array                      |
| STRUCT        | java.sql.Struct or java.sql.SQLData |
| REF           | java.sql.Ref                        |
| DATALINK      | java.net.URL                        |
| JAVA_OBJECT   | underlying Java class               |
| ROWID         | java.sql.RowId                      |
| NCHAR         | String                              |
| NVARCHAR      | String                              |
| LONGNVARCHAR  | String                              |
| NCLOB         | java.sql.NClob                      |
| SQLXML        | java.sql.SQLXML                     |

# TABLE B-4 Java Object Types Mapped to JDBC Types

PreparedStatement.setObject, PreparedStatement.setNull, RowSet.setNull and
RowSet.setObject use the mapping shown in this table when no parameter
specifying a target JDBC type is provided.

| Java Object Type     | JDBC Type                                                   |
|----------------------|-------------------------------------------------------------|
| String               | CHAR, VARCHAR, LONGVARCHAR, NCHAR, NVARCHAR or LONGNVARCHAR |
| java.math.BigDecimal | NUMERIC                                                     |
| Boolean              | BIT or BOOLEAN                                              |
| Byte                 | TINYINT                                                     |
| Short                | SMALLINT                                                    |
| Integer              | INTEGER                                                     |
| Long                 | BIGINT                                                      |
| Float                | REAL                                                        |
| Double               | DOUBLE                                                      |
| byte[]               | BINARY, VARBINARY, or LONGVARBINARY                         |
| java.sql.Date        | DATE                                                        |
| java.sql.Time        | TIME                                                        |
| java.sql.Timestamp   | TIMESTAMP                                                   |
| java.sql.Clob        | CLOB                                                        |
| java.sql.Blob        | BLOB                                                        |
| java.sql.Array       | ARRAY                                                       |
| java.sql.Struct      | STRUCT                                                      |
| java.sql.Ref         | REF                                                         |
| java.net.URL         | DATALINK                                                    |
| Java class           | JAVA_OBJECT                                                 |
| java.sql.RowId       | ROWID                                                       |
| java.sql.NClob       | NCLOB                                                       |
| java.sql.SQLXML      | SQLXML                                                      |

# TABLE B-5 Conversions by setObject and setNull from Java Object Types to JDBC Types

This table shows which JDBC types may be specified as the target JDBC type to the
methods PreparedStatement.setObject, PreparedStatement.setNull, RowSet.setNull
and RowSet.setObject.

| Java object type     | TINYINT | SMALLINT | INTEGER | BIGINT | REAL | FLOAT | DOUBLE | DECIMAL | NUMERIC | BIT | BOOLEAN | CHAR | VARCHAR | LONGVARCHAR | BINARY | VARBINARY | LONGVARBINARY | DATE | TIME | TIMESTAMP | ARRAY | BLOB | CLOB | STRUCT | REF | DATALINK | JAVA_OBJECT | ROWID | NCHAR | NVARCHAR | NCLOB | SQLXML |
|----------------------|---------|----------|---------|--------|------|-------|--------|---------|---------|-----|---------|------|---------|-------------|--------|-----------|---------------|------|------|-----------|-------|------|------|--------|-----|----------|-------------|-------|-------|----------|-------|--------|
| String               | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           | x      | x         | x             | x    | x    | x         |       |      |      |        |     |          |             |       | x     | x        |       |        |
| java.math.BigDecimal | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| Boolean              | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| Byte                 | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| Short                | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| Integer              | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| Long                 | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| Float                | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| Double               | x       | x        | x       | x      | x    | x     | x      | x       | x       | x   | x       | x    | x       | x           |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| byte[]               |         |          |         |        |      |       |        |         |         |     |         |      |         |             | x      | x         | x             |      |      |           |       |      |      |        |     |          |             |       |       |          |       |        |
| java.sql.Date        |         |          |         |        |      |       |        |         |         |     |         | x    | x       | x           |        |           |               | x    |      | x         |       |      |      |        |     |          |             |       |       |          |       |        |
| java.sql.Time        |         |          |         |        |      |       |        |         |         |     |         | x    | x       | x           |        |           |               |      | x    | x         |       |      |      |        |     |          |             |       |       |          |       |        |
| java.sql.Timestamp   |         |          |         |        |      |       |        |         |         |     |         | x    | x       | x           |        |           |               | x    | x    | x         |       |      |      |        |     |          |             |       |       |          |       |        |
| java.sql.Array       |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           | x     |      |      |        |     |          |             |       |       |          |       |        |
| java.sql.Blob        |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       | x    |      |        |     |          |             |       |       |          |       |        |
| java.sql.Clob        |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      | x    |        |     |          |             |       |       |          |       |        |
| java.sql.Struct      |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      |      | x      |     |          |             |       |       |          |       |        |
| java.sql.Ref         |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      |      |        | x   |          |             |       |       |          |       |        |
| java.net.URL         |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      |      |        |     | x        |             |       |       |          |       |        |
| Java class           |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      |      |        |     |          | x           |       |       |          |       |        |
| java.sql.RowId       |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      |      |        |     |          |             | x     |       |          |       |        |
| java.sql.NClob       |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          | x     |        |
| java.sql.SQLXML      |         |          |         |        |      |       |        |         |         |     |         |      |         |             |        |           |               |      |      |           |       |      |      |        |     |          |             |       |       |          |       | x      |

Notes:

- Blank cells mean "not a valid target type for `setObject`/`setNull`"; `x` means the driver must support the
  conversion.
- JDBC 4.1+ adds the `LONGNVARCHAR` column (`String` also targets it), plus rows for `BigInteger`, `java.util.Calendar`,
  and `java.util.Date`; JDBC 4.2+ adds `java.time` rows.
- `java.sql.Date`/`Time` do not cross-map to each other — only `Timestamp` targets all three of `DATE`, `TIME`,
  `TIMESTAMP`.

# TABLE B-6 Type Conversions Supported by ResultSet getter Methods

This table shows which JDBC types may be returned by ResultSet getter methods. A
bold X indicates the method recommended for retrieving a JDBC type. A plain x
indicates for which JDBC types it is possible to use a getter method.

That image is the JDBC spec's **Table B-6 — Use of `ResultSet` getter Methods to Retrieve JDBC Data Types**. Converted
(`**X**` = recommended getter, `x` = supported conversion, blank = not supported):

| ResultSet getter    | TINYINT | SMALLINT | INTEGER | BIGINT | REAL  | FLOAT | DOUBLE | DECIMAL | NUMERIC | BIT   | BOOLEAN | CHAR  | VARCHAR | LONGVARCHAR | BINARY | VARBINARY | LONGVARBINARY | DATE  | TIME  | TIMESTAMP | CLOB  | BLOB  | ARRAY | REF   | DATALINK | STRUCT | JAVA_OBJECT | ROWID | NCHAR | NVARCHAR | LONGNVARCHAR | NCLOB | SQLXML |
|---------------------|---------|----------|---------|--------|-------|-------|--------|---------|---------|-------|---------|-------|---------|-------------|--------|-----------|---------------|-------|-------|-----------|-------|-------|-------|-------|----------|--------|-------------|-------|-------|----------|--------------|-------|--------|
| getByte             | **X**   | x        | x       | x      | x     | x     | x      | x       | x       | x     | x       | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             | x     |       |          |              |       |        |
| getShort            | x       | **X**    | x       | x      | x     | x     | x      | x       | x       | x     | x       | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getInt              | x       | x        | **X**   | x      | x     | x     | x      | x       | x       | x     | x       | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getLong             | x       | x        | x       | **X**  | x     | x     | x      | x       | x       | x     | x       | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getFloat            | x       | x        | x       | x      | **X** | x     | x      | x       | x       | x     | x       | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getDouble           | x       | x        | x       | x      | x     | **X** | **X**  | x       | x       | x     | x       | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getBigDecimal       | x       | x        | x       | x      | x     | x     | x      | **X**   | **X**   | x     | x       | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getBoolean          | x       | x        | x       | x      | x     | x     | x      | x       | x       | **X** | **X**   | x     | x       | x           |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getString           | x       | x        | x       | x      | x     | x     | x      | x       | x       | x     | x       | **X** | **X**   | x           | x      | x         | x             | x     | x     | x         |       |       |       |       | x        |        |             |       | x     | x        | x            |       |        |
| getNString          | x       | x        | x       | x      | x     | x     | x      | x       | x       | x     | x       | x     | x       | x           | x      | x         | x             | x     | x     | x         |       |       |       |       | x        |        |             |       | **X** | **X**    | x            |       |        |
| getBytes            |         |          |         |        |       |       |        |         |         |       |         |       |         |             | **X**  | **X**     | x             |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getDate             |         |          |         |        |       |       |        |         |         |       |         | x     | x       | x           |        |           |               | **X** |       | x         |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getTime             |         |          |         |        |       |       |        |         |         |       |         | x     | x       | x           |        |           |               |       | **X** | x         |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getTimestamp        |         |          |         |        |       |       |        |         |         |       |         | x     | x       | x           |        |           |               | x     | x     | **X**     |       |       |       |       |          |        |             |       |       |          |              |       |        |
| getAsciiStream      |         |          |         |        |       |       |        |         |         |       |         | x     | x       | **X**       | x      | x         | x             |       |       |           | x     |       |       |       |          |        |             |       |       |          |              | x     |        |
| getBinaryStream     |         |          |         |        |       |       |        |         |         |       |         |       |         |             | x      | x         | **X**         |       |       |           |       | x     |       |       |          |        |             |       |       |          |              |       | x      |
| getCharacterStream  |         |          |         |        |       |       |        |         |         |       |         | x     | x       | **X**       | x      | x         | x             |       |       |           | x     |       |       |       |          |        |             |       | x     | x        | x            | x     | x      |
| getNCharacterStream |         |          |         |        |       |       |        |         |         |       |         | x     | x       | x           | x      | x         | x             |       |       |           | x     |       |       |       |          |        |             |       | x     | x        | **X**        | x     | x      |
| getClob             |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           | **X** |       |       |       |          |        |             |       |       |          |              | x     |        |
| getNClob            |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           | x     |       |       |       |          |        |             |       |       |          |              | **X** |        |
| getBlob             |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           |       | **X** |       |       |          |        |             |       |       |          |              |       |        |
| getArray            |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           |       |       | **X** |       |          |        |             |       |       |          |              |       |        |
| getRef              |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           |       |       |       | **X** |          |        |             |       |       |          |              |       |        |
| getURL              |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           |       |       |       |       | **X**    |        |             |       |       |          |              |       |        |
| getObject           | x       | x        | x       | x      | x     | x     | x      | x       | x       | x     | x       | x     | x       | x           | x      | x         | x             | x     | x     | x         | x     | x     | x     | x     | x        | **X**  | **X**       | x     | x     | x        | x            | x     | x      |
| getRowId            |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           |       |       |       |       |          |        |             | **X** |       |          |              |       |        |
| getSQLXML           |         |          |         |        |       |       |        |         |         |       |         |       |         |             |        |           |               |       |       |           |       |       |       |       |          |        |             |       |       |          |              |       | **X**  |

Notes:

- Read a column as: "this JDBC type can be fetched with this getter"; the bold `X` is the JDBC-recommended getter for
  that SQL type.
- Worth flagging because it surprises people: `getObject` accepts every listed SQL type; `getString` covers all numeric
  types plus binary and date/time; and `getBinaryStream` in this revision lists `BLOB` and `SQLXML` (later revisions
  narrowed it to the binary types).
- The column order here follows the printed table (`CLOB, BLOB, ARRAY, REF, …`); the current JDBC 4.3 spec presents the
  same content as prose rather than a matrix.