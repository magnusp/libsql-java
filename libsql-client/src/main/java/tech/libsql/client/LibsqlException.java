package tech.libsql.client;

import tech.libsql.hrana.codec.HranaError;

public class LibsqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final int httpStatus;

    public LibsqlException(String message, String code, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public LibsqlException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
        this.httpStatus = 0;
    }

    public LibsqlException(HranaError error) {
        super(error.message());
        this.code = error.code();
        this.httpStatus = 200;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
