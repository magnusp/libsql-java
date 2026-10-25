# JDBC 4.3 Appendix B — Data Type Conversion Tables

Source: JDBC 4.3 Specification (JSR 000221, Maintenance Release 3, July 2017),
Appendix B, pages 189–201.

Reproduced as published, with the typographical errors listed below corrected.
Tables B-5 and B-6 are prose tables in 4.3; they were matrices in 4.0/4.1.

## Errata corrected in this copy

| Published                                  | Corrected                    | Location           |
|--------------------------------------------|------------------------------|--------------------|
| `java.sql.array`                           | `java.sql.Array`             | Table B-1          |
| `LONVARBINARY`                             | `LONGVARBINARY`              | Tables B-5 and B-6 |
| `java.time.OffsetDatetime`                 | `java.time.OffsetDateTime`   | Tables B-4 and B-5 |
| `Java Object Type` (column of getter names) | `ResultSet getter`          | Table B-6          |

## B.1 JDBC Types Mapped to Java Types

TABLE B-1 shows the conceptual correspondence between JDBC types and Java types.
A programmer should write code with this mapping in mind. For example, if a value
in the database is a SMALLINT, a short should be the data type used in a JDBC
application.

All CallableStatement getter methods except for getObject use this mapping. The
getObject methods for both the CallableStatement and ResultSet interfaces use the
mapping in TABLE B-3.

**TABLE B-1 — JDBC Types Mapped to Java Types**

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
| DISTINCT      | Mapping of underlying type |
| STRUCT        | java.sql.Struct            |
| REF           | java.sql.Ref               |
| DATALINK      | java.net.URL               |
| JAVA_OBJECT   | Underlying Java class      |
| ROWID         | java.sql.RowId             |
| NCHAR         | String                     |
| NVARCHAR      | String                     |
| LONGNVARCHAR  | String                     |
| NCLOB         | java.sql.NClob             |
| SQLXML        | java.sql.SQLXML            |

## B.2 Java Types Mapped to JDBC Types

TABLE B-2 shows the mapping a driver should use for the updater methods in the
ResultSet interface and for IN parameters. PreparedStatement setter methods and
RowSet setter methods use this table for mapping an IN parameter, which is a Java
type, to the JDBC type that will be sent to the database. Note that the setObject
methods for these two interfaces use the mapping shown in TABLE B-4.

**TABLE B-2 — Standard Mapping from Java Types to JDBC Types**

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

## B.3 JDBC Types Mapped to Java Object Types

ResultSet.getObject and CallableStatement.getObject use the mapping shown in
TABLE B-3 for standard mappings.

> **Note** — The JDBC 1.0 specification defined the Java object mapping for the
> SMALLINT and TINYINT JDBC types to be Integer. The Java language did not
> include the Byte and Short data types when the JDBC 1.0 specification was
> finalized. The mapping of SMALLINT and TINYINT to Integer is maintained to
> preserve backwards compatibility.

**TABLE B-3 — Mapping from JDBC Types to Java Object Types**

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
| JAVA_OBJECT   | Underlying Java class               |
| ROWID         | java.sql.RowId                      |
| NCHAR         | String                              |
| NVARCHAR      | String                              |
| LONGNVARCHAR  | String                              |
| NCLOB         | java.sql.NClob                      |
| SQLXML        | java.sql.SQLXML                     |

## B.4 Java Object Types Mapped to JDBC Types

PreparedStatement.setObject, PreparedStatement.setNull, RowSet.setNull and
RowSet.setObject use the mapping shown in TABLE B-4 when no parameter specifying
a target JDBC type is provided.

**TABLE B-4 — Mapping from Java Object Types to JDBC Types**

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
| java.math.BigInteger | BIGINT                                                      |
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
| java.util.Calendar   | TIMESTAMP                                                   |
| java.util.Date       | TIMESTAMP                                                   |
| java.time.LocalDate  | DATE                                                        |
| java.time.LocalTime  | TIME                                                        |
| java.time.LocalDateTime | TIMESTAMP                                                |
| java.time.OffsetTime | TIME_WITH_TIMEZONE                                          |
| java.time.OffsetDateTime | TIMESTAMP_WITH_TIMEZONE                                 |

## B.5 Conversions by setObject and setNull from Java Object Types to JDBC Types

TABLE B-5 shows which JDBC types may be specified as the target JDBC type to the
methods PreparedStatement.setObject, PreparedStatement.setNull,
RowSet.setNull, and RowSet.setObject.

**TABLE B-5 — Conversions Performed by setObject and setNull Between Java Object Types and Target JDBC Types**

| Java Object Type         | Supported JDBC Type                                                                                                                                                             |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| String                   | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR, BINARY, VARBINARY, LONGVARBINARY, DATE, TIME, TIMESTAMP, NCHAR, NVARCHAR, LONGNVARCHAR |
| java.math.BigDecimal     | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| Boolean                  | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| Byte                     | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| Short                    | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| Integer                  | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| Long                     | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| Float                    | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| Double                   | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                             |
| byte[]                   | BINARY, VARBINARY, or LONGVARBINARY                                                                                                                                             |
| java.math.BigInteger     | BIGINT, CHAR, VARCHAR, LONGVARCHAR                                                                                                                                              |
| java.sql.Date            | CHAR, VARCHAR, LONGVARCHAR, DATE, TIMESTAMP                                                                                                                                     |
| java.sql.Time            | CHAR, VARCHAR, LONGVARCHAR, TIME, TIMESTAMP                                                                                                                                     |
| java.sql.Timestamp       | CHAR, VARCHAR, LONGVARCHAR, DATE, TIME, TIMESTAMP                                                                                                                               |
| java.sql.Array           | ARRAY                                                                                                                                                                           |
| java.sql.Blob            | BLOB                                                                                                                                                                            |
| java.sql.Clob            | CLOB                                                                                                                                                                            |
| java.sql.Struct          | STRUCT                                                                                                                                                                          |
| java.sql.Ref             | REF                                                                                                                                                                             |
| java.net.URL             | DATALINK                                                                                                                                                                        |
| Java class               | JAVA_OBJECT                                                                                                                                                                     |
| java.sql.RowId           | ROWID                                                                                                                                                                           |
| java.sql.NClob           | NCLOB                                                                                                                                                                           |
| java.sql.SQLXML          | SQLXML                                                                                                                                                                          |
| java.util.Calendar       | CHAR, VARCHAR, LONGVARCHAR, DATE, TIME, TIMESTAMP, ARRAY                                                                                                                        |
| java.util.Date           | CHAR, VARCHAR, LONGVARCHAR, DATE, TIME, TIMESTAMP, ARRAY                                                                                                                        |
| java.time.LocalDate      | CHAR, VARCHAR, LONGVARCHAR, DATE                                                                                                                                                |
| java.time.LocalTime      | CHAR, VARCHAR, LONGVARCHAR, TIME                                                                                                                                                |
| java.time.LocalDateTime  | CHAR, VARCHAR, LONGVARCHAR, DATE, TIME, TIMESTAMP                                                                                                                               |
| java.time.OffsetTime     | CHAR, VARCHAR, LONGVARCHAR, TIME_WITH_TIMEZONE                                                                                                                                  |
| java.time.OffsetDateTime | CHAR, VARCHAR, LONGVARCHAR, TIME_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE                                                                                                         |

## B.6 Type Conversions Supported by ResultSet getter Methods

TABLE B-6 shows which JDBC types may be returned by ResultSet getter methods.
This table also shows the conversions used by the SQLInput reader methods, except
that they use only the recommended conversions.

**TABLE B-6 — Use of ResultSet getter Methods to Retrieve JDBC Data Types**

| ResultSet getter    | Recommended JDBC Type | Supported JDBC Type                                                                                                                                                                                                     |
|---------------------|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| getByte             | TINYINT               | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR, ROWID                                                                                              |
| getShort            | SMALLINT              | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                                                                     |
| getInt              | INTEGER               | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                                                                     |
| getLong             | BIGINT                | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                                                                     |
| getFloat            | REAL                  | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                                                                     |
| getDouble           | FLOAT, DOUBLE         | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                                                                     |
| getBigDecimal       | DECIMAL, NUMERIC      | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                                                                     |
| getBoolean          | BIT, BOOLEAN          | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR                                                                                                     |
| getString           | CHAR, VARCHAR         | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR, BINARY, VARBINARY, LONGVARBINARY, DATE, TIME, TIMESTAMP, DATALINK, NCHAR, NVARCHAR, LONGNVARCHAR    |
| getNString          | NCHAR, NVARCHAR       | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR, BINARY, VARBINARY, LONGVARBINARY, DATE, TIME, TIMESTAMP, DATALINK, NCHAR, NVARCHAR, LONGNVARCHAR    |
| getBytes            | BINARY, VARBINARY     | BINARY, VARBINARY, LONGVARBINARY                                                                                                                                                                                        |
| getDate             | DATE                  | CHAR, VARCHAR, LONGVARCHAR, DATE, TIMESTAMP                                                                                                                                                                             |
| getTime             | TIME                  | CHAR, VARCHAR, LONGVARCHAR, TIME, TIMESTAMP                                                                                                                                                                             |
| getTimestamp        | TIMESTAMP             | CHAR, VARCHAR, LONGVARCHAR, DATE, TIME, TIMESTAMP                                                                                                                                                                       |
| getAsciiStream      | LONGVARCHAR           | CHAR, VARCHAR, LONGVARCHAR, BINARY, VARBINARY, LONGVARBINARY, CLOB, NCLOB                                                                                                                                               |
| getBinaryStream     | LONGVARBINARY         | BINARY, VARBINARY, LONGVARBINARY                                                                                                                                                                                        |
| getCharacterStream  | LONGVARCHAR           | CHAR, VARCHAR, LONGVARCHAR, BINARY, VARBINARY, LONGVARBINARY, CLOB, NCHAR, NVARCHAR, LONGNVARCHAR, NCLOB, SQLXML                                                                                                        |
| getNCharacterStream | LONGNVARCHAR          | CHAR, VARCHAR, LONGVARCHAR, BINARY, VARBINARY, LONGVARBINARY, CLOB, NCHAR, NVARCHAR, LONGNVARCHAR, NCLOB, SQLXML                                                                                                        |
| getClob             | CLOB                  | CLOB, NCLOB                                                                                                                                                                                                             |
| getNClob            | NCLOB                 | CLOB, NCLOB                                                                                                                                                                                                             |
| getBlob             | BLOB                  | BLOB                                                                                                                                                                                                                    |
| getArray            | ARRAY                 | ARRAY                                                                                                                                                                                                                   |
| getRef              | REF                   | REF                                                                                                                                                                                                                     |
| getURL              | DATALINK              | DATALINK                                                                                                                                                                                                                |
| getObject           | STRUCT, JAVA_OBJECT   | TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT, DOUBLE, DECIMAL, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR, BINARY, VARBINARY, LONGVARBINARY, DATE, TIME, TIMESTAMP, CLOB, BLOB, ARRAY, REF, DATALINK, STRUCT, JAVA_OBJECT, ROWID, NCHAR, NVARCHAR, LONGNVARCHAR, NCLOB, SQLXML, TIME_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE |
| getRowId            | ROWID                 | ROWID                                                                                                                                                                                                                   |
| getSQLXML           | SQLXML                | SQLXML                                                                                                                                                                                                                  |
