package com.github.magnusp.libsql.hrana.codec;

import java.util.Arrays;
import java.util.Objects;

public sealed interface Value {

    record Null() implements Value {
        public static final Null INSTANCE = new Null();
    }

    record Integer(long value) implements Value {}

    record Float(double value) implements Value {}

    record Text(String value) implements Value {
        public Text {
            Objects.requireNonNull(value, "Text value cannot be null");
        }
    }

    record Blob(byte[] bytes) implements Value {
        public Blob {
            Objects.requireNonNull(bytes, "Blob bytes cannot be null");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Blob other)) return false;
            return Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    static Value ofNull() {
        return Null.INSTANCE;
    }

    static Value of(long val) {
        return new Integer(val);
    }

    static Value of(double val) {
        return new Float(val);
    }

    static Value of(String val) {
        return val == null ? ofNull() : new Text(val);
    }

    static Value of(byte[] val) {
        return val == null ? ofNull() : new Blob(val);
    }
}
