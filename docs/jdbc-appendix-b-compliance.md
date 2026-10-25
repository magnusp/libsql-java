# JDBC 4.3 Appendix B compliance profile

This page records the JDBC 4.3 Appendix B status of `libsql-jdbc`. The oracle is the specification in [`../vendor/JDBC4-TYPE-MAPPING.md`](../vendor/JDBC4-TYPE-MAPPING.md), not sqlite-jdbc. Every row below names the test that proves it.

## How to read this profile

- `supported` means the driver maps the row to a Hrana value kind and its declared type directly.
- `emulated` means SQLite has no distinct storage for the JDBC type. The driver supplies the Java type through a wrapper or a text or integer codec, and the observable Java type matches the appendix.
- `N/A` means SQLite has no interoperable representation. The driver throws `SQLFeatureNotSupportedException`.

Five types are N/A. They are `ARRAY`, `STRUCT`, `REF`, `JAVA_OBJECT`, and `DISTINCT`.

The unit suites are `Jdbc4AppendixBReadTest`, `Jdbc4AppendixBSetObjectTest`, and `Jdbc4AppendixBProfileTest`. The server suite is `Jdbc4AppendixBTypeMappingIT`. Both run from a clean checkout with the commands in the root [`README.md`](../README.md).

## Table B-1, JDBC types mapped to Java types

Table B-1 is observed through the natural ResultSet getter for each JDBC type.

| Appendix B row | Status | Reason | Test |
|---|---|---|---|
| `CHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `VARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `LONGVARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NUMERIC` | emulated | SQLite has no decimal type. The driver reads the stored value as BigDecimal. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `DECIMAL` | emulated | SQLite has no decimal type. The driver reads the stored value as BigDecimal. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BIT` | emulated | SQLite has no boolean type. The driver reads integer 0 or 1 as Boolean. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BOOLEAN` | emulated | SQLite has no boolean type. The driver reads integer 0 or 1 as Boolean. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `TINYINT` | supported | A narrowed Hrana integer maps directly to byte. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `SMALLINT` | supported | A narrowed Hrana integer maps directly to short. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `INTEGER` | supported | A Hrana integer maps directly to int. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BIGINT` | supported | A Hrana integer maps directly to long. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `REAL` | supported | A Hrana real maps directly to float. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `FLOAT` | supported | A Hrana real maps directly to double. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `DOUBLE` | supported | A Hrana real maps directly to double. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BINARY` | supported | A Hrana blob maps directly to byte[]. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `VARBINARY` | supported | A Hrana blob maps directly to byte[]. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `LONGVARBINARY` | supported | A Hrana blob maps directly to byte[]. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `DATE` | emulated | SQLite has no date type. The driver parses ISO text into java.sql.Date. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `TIME` | emulated | SQLite has no time type. The driver parses text into java.sql.Time. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `TIMESTAMP` | emulated | SQLite has no timestamp type. The driver parses text into java.sql.Timestamp. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `CLOB` | emulated | The driver wraps stored text in java.sql.Clob. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BLOB` | supported | A Hrana blob maps directly to java.sql.Blob. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `ARRAY` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `DISTINCT` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `STRUCT` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `REF` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `DATALINK` | emulated | The driver parses stored text into java.net.URL. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `JAVA_OBJECT` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `ROWID` | emulated | The driver wraps stored text or blob bytes in java.sql.RowId. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NVARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `LONGNVARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NCLOB` | emulated | The driver wraps stored text in java.sql.NClob. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `SQLXML` | emulated | The driver wraps stored text in java.sql.SQLXML. | `Jdbc4AppendixBReadTest#b1NaturalGetterReturnsDeclaredJavaType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |

## Table B-2, Java types mapped to JDBC types

Table B-2 is observed through the standard setter for each Java type.

| Appendix B row | Status | Reason | Test |
|---|---|---|---|
| `String` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.math.BigDecimal` | emulated | Binds decimal text. SQLite has no decimal type. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `boolean` | emulated | Binds integer 0 or 1. SQLite has no boolean type. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `byte` | supported | Binds a narrowed Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `short` | supported | Binds a narrowed Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `int` | supported | Binds a Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `long` | supported | Binds a Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `float` | supported | Binds a Hrana real. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `double` | supported | Binds a Hrana real. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `byte[]` | supported | Binds a Hrana blob. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.Date` | emulated | Binds SQLite date text. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.Time` | emulated | Binds SQLite time text. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.Timestamp` | emulated | Binds SQLite timestamp text. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.Clob` | emulated | Reads the character stream and binds text. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.Blob` | supported | Reads the binary stream and binds a blob. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.Array` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.Struct` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.Ref` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.net.URL` | emulated | Binds the URL as text. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `Java class` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.RowId` | emulated | Binds the RowId bytes as a Hrana blob. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.NClob` | emulated | Reads the character stream and binds text. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |
| `java.sql.SQLXML` | emulated | Reads the SQLXML text and binds it. | `Jdbc4AppendixBTypeMappingIT#b2StandardSettersMapJavaTypeToJdbcType` |

## Table B-3, JDBC types mapped to Java object types

Table B-3 is observed through `getObject(int)` for each declared type.

| Appendix B row | Status | Reason | Test |
|---|---|---|---|
| `CHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `VARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `LONGVARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NUMERIC` | emulated | SQLite has no decimal type. The driver reads the stored value as BigDecimal. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `DECIMAL` | emulated | SQLite has no decimal type. The driver reads the stored value as BigDecimal. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BIT` | emulated | SQLite has no boolean type. The driver reads integer 0 or 1 as Boolean. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BOOLEAN` | emulated | SQLite has no boolean type. The driver reads integer 0 or 1 as Boolean. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `TINYINT` | supported | A narrowed Hrana integer maps directly to byte. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `SMALLINT` | supported | A narrowed Hrana integer maps directly to short. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `INTEGER` | supported | A Hrana integer maps directly to int. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BIGINT` | supported | A Hrana integer maps directly to long. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `REAL` | supported | A Hrana real maps directly to float. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `FLOAT` | supported | A Hrana real maps directly to double. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `DOUBLE` | supported | A Hrana real maps directly to double. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BINARY` | supported | A Hrana blob maps directly to byte[]. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `VARBINARY` | supported | A Hrana blob maps directly to byte[]. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `LONGVARBINARY` | supported | A Hrana blob maps directly to byte[]. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `DATE` | emulated | SQLite has no date type. The driver parses ISO text into java.sql.Date. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `TIME` | emulated | SQLite has no time type. The driver parses text into java.sql.Time. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `TIMESTAMP` | emulated | SQLite has no timestamp type. The driver parses text into java.sql.Timestamp. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `DISTINCT` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `CLOB` | emulated | The driver wraps stored text in java.sql.Clob. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `BLOB` | supported | A Hrana blob maps directly to java.sql.Blob. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `ARRAY` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `STRUCT` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `REF` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `DATALINK` | emulated | The driver parses stored text into java.net.URL. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `JAVA_OBJECT` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `ROWID` | emulated | The driver wraps stored text or blob bytes in java.sql.RowId. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NVARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `LONGNVARCHAR` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `NCLOB` | emulated | The driver wraps stored text in java.sql.NClob. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |
| `SQLXML` | emulated | The driver wraps stored text in java.sql.SQLXML. | `Jdbc4AppendixBReadTest#b3GetObjectReturnsDeclaredObjectType`, `Jdbc4AppendixBTypeMappingIT#b1AndB3ReadPathAgainstRealDdl` |

## Table B-4, Java object types mapped to JDBC types

Table B-4 is observed through `setObject(int, Object)` with no target type.

| Appendix B row | Status | Reason | Test |
|---|---|---|---|
| `String` | supported | SQLite text maps directly to String. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.math.BigDecimal` | emulated | Binds decimal text. SQLite has no decimal type. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Boolean` | emulated | Binds integer 0 or 1. SQLite has no boolean type. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Byte` | supported | Binds a narrowed Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Short` | supported | Binds a narrowed Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Integer` | supported | Binds a Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Long` | supported | Binds a Hrana integer. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Float` | supported | Binds a Hrana real. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Double` | supported | Binds a Hrana real. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `byte[]` | supported | Binds a Hrana blob. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.math.BigInteger` | supported | Binds a Hrana integer after an exact long range check. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.Date` | emulated | Binds SQLite date text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.Time` | emulated | Binds SQLite time text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.Timestamp` | emulated | Binds SQLite timestamp text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.Clob` | emulated | Reads the character stream and binds text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.Blob` | supported | Reads the binary stream and binds a blob. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.Array` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.Struct` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.Ref` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.net.URL` | emulated | Binds the URL as text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `Java class` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.RowId` | emulated | Binds the RowId bytes as a Hrana blob. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.NClob` | emulated | Reads the character stream and binds text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.sql.SQLXML` | emulated | Reads the SQLXML text and binds it. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.util.Calendar` | emulated | Binds SQLite timestamp text from the calendar instant. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.util.Date` | emulated | Binds SQLite timestamp text from the date instant. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.time.LocalDate` | emulated | Binds SQLite date text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.time.LocalTime` | emulated | Binds SQLite time text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.time.LocalDateTime` | emulated | Binds SQLite timestamp text. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.time.OffsetTime` | emulated | Binds ISO-8601 text with an offset. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |
| `java.time.OffsetDateTime` | emulated | Binds ISO-8601 text with an offset. | `Jdbc4AppendixBTypeMappingIT#b4SetObjectMapsJavaObjectTypeToJdbcType` |

## Table B-5, conversions by setObject and setNull

Table B-5 is observed through `setObject(int, Object, int)`. A listed pair must convert. An unlisted pair must throw.

| Appendix B row | Status | Reason | Test |
|---|---|---|---|
| `String` | supported | Every listed target is a defined conversion. Binary targets bind text bytes. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.math.BigDecimal` | emulated | Numeric targets narrow through BigDecimal. DECIMAL and NUMERIC bind decimal text. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Boolean` | emulated | Binds integer 0 or 1 for numeric and boolean targets. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Byte` | supported | Every listed target is a defined numeric or text conversion. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Short` | supported | Every listed target is a defined numeric or text conversion. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Integer` | supported | Every listed target is a defined numeric or text conversion. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Long` | supported | Every listed target is a defined numeric or text conversion. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Float` | supported | Every listed target is a defined numeric or text conversion. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Double` | supported | Every listed target is a defined numeric or text conversion. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `byte[]` | supported | BINARY, VARBINARY and LONGVARBINARY bind the blob bytes. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.math.BigInteger` | supported | BIGINT binds an integer. Text targets bind the decimal form. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.Date` | emulated | DATE and TIMESTAMP bind the SQLite text form. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.Time` | emulated | TIME and TIMESTAMP bind the SQLite text form. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.Timestamp` | emulated | DATE, TIME and TIMESTAMP bind the SQLite text form. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.Array` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.Blob` | supported | BLOB binds the blob bytes. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.Clob` | emulated | CLOB reads the character stream and binds text. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.Struct` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.Ref` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.net.URL` | emulated | DATALINK binds the URL text. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `Java class` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `java.sql.RowId` | emulated | ROWID binds the RowId bytes. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.NClob` | emulated | NCLOB reads the character stream and binds text. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.sql.SQLXML` | emulated | SQLXML binds the XML text. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.util.Calendar` | emulated | Temporal targets bind text. The ARRAY target is N/A. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.util.Date` | emulated | Temporal targets bind text. The ARRAY target is N/A. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.time.LocalDate` | emulated | DATE and text targets bind the SQLite text form. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.time.LocalTime` | emulated | TIME and text targets bind the SQLite text form. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.time.LocalDateTime` | emulated | DATE, TIME, TIMESTAMP and text targets bind the SQLite text form. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.time.OffsetTime` | emulated | TIME_WITH_TIMEZONE and text targets bind ISO-8601 text with an offset. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |
| `java.time.OffsetDateTime` | emulated | TIME_WITH_TIMEZONE, TIMESTAMP_WITH_TIMEZONE and text targets bind ISO-8601 text with an offset. | `Jdbc4AppendixBTypeMappingIT#b5SetObjectConvertsToExplicitTargetType`, `Jdbc4AppendixBSetObjectTest#b5SetObjectRejectsUnlistedTargetTypes` |

## Table B-6, ResultSet getter conversions

Table B-6 is observed through each getter against its recommended declared type.

| Appendix B row | Status | Reason | Test |
|---|---|---|---|
| `getByte` | supported | Reads a numeric, boolean or text value as a byte. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getShort` | supported | Reads a numeric, boolean or text value as a short. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getInt` | supported | Reads a numeric, boolean or text value as an int. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getLong` | supported | Reads a numeric, boolean or text value as a long. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getFloat` | supported | Reads a numeric, boolean or text value as a float. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getDouble` | supported | Reads a numeric, boolean or text value as a double. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getBigDecimal` | supported | Reads a numeric, boolean or text value as BigDecimal. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getBoolean` | emulated | Reads integer 0 or 1, and the text true or false, as Boolean. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getString` | supported | Reads text and the listed scalar types as String. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getNString` | supported | Reads text and the listed scalar types as String. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getBytes` | supported | Reads binary values and the listed types as byte[]. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getDate` | emulated | Parses the supported text forms into java.sql.Date. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getTime` | emulated | Parses the supported text forms into java.sql.Time. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getTimestamp` | emulated | Parses the supported text forms into java.sql.Timestamp. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getAsciiStream` | emulated | Returns the text bytes as a US-ASCII stream. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getBinaryStream` | supported | Returns the blob bytes as a stream. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getCharacterStream` | emulated | Returns a reader over the stored text or bytes. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getNCharacterStream` | emulated | Returns a reader over the stored text or bytes. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getClob` | emulated | Wraps stored text in java.sql.Clob. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getNClob` | emulated | Wraps stored text in java.sql.NClob. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getBlob` | supported | Wraps stored bytes in java.sql.Blob. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getArray` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `getRef` | N/A | SQLite has no interoperable representation. The driver throws SQLFeatureNotSupportedException. | `Jdbc4AppendixBProfileTest#everyUnsupportedDeclaredTypeThrowsFeatureNotSupported` |
| `getURL` | emulated | Parses stored text into java.net.URL. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getObject` | supported | Returns the Table B-3 object type for each supported declared type. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getRowId` | emulated | Wraps stored text or bytes in java.sql.RowId. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |
| `getSQLXML` | emulated | Wraps stored text in java.sql.SQLXML. | `Jdbc4AppendixBReadTest#b6GetterHonoursSupportedJdbcTypes`, `Jdbc4AppendixBTypeMappingIT#b6GetterReadsRecommendedTypeFromRealDdl` |

## Documented exception

An expression column has no declared type. `SELECT 1 + 1` reports a null `decltype`, so the driver cannot resolve a JDBC type before it sees the value. The driver falls back to the storage class of the value and stays permissive. This fallback is an exception, not a supported Appendix B row, because the appendix assumes a declared JDBC type.

The fallback is covered by `Jdbc4AppendixBProfileTest#expressionFallbackStaysPermissive`. The same exception is recorded in `JdbcTypeMapping.Support.FALLBACK`.

## Conformance quarantines

The xerial conformance suite keeps its selected tests except for four methods whose expectations Appendix B replaces. The list and the reason for each one are in [`../libsql-conformance/README.md`](../libsql-conformance/README.md). They still run in the `xerial-control` profile.
