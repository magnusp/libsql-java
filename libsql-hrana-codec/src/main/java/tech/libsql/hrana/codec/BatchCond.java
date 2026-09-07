package tech.libsql.hrana.codec;

import java.util.List;

public sealed interface BatchCond {
    record StepOk(int step) implements BatchCond {}
    record StepError(int step) implements BatchCond {}
    record Not(BatchCond cond) implements BatchCond {}
    record And(List<BatchCond> conds) implements BatchCond {}
    record Or(List<BatchCond> conds) implements BatchCond {}
    record IsAutocommit() implements BatchCond {
        public static final IsAutocommit INSTANCE = new IsAutocommit();
    }
}
