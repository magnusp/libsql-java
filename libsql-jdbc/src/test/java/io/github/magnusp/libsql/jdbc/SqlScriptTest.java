package io.github.magnusp.libsql.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptTest {

    @Test
    void returnsASingleStatementWhenThereIsNoTerminator() {
        assertThat(SqlScript.split("select 1")).containsExactly("select 1");
    }

    @Test
    void splitsStatementsOnTopLevelSemicolons() {
        assertThat(SqlScript.split("insert into t values (1); insert into t values (2)"))
                .containsExactly("insert into t values (1)", "insert into t values (2)");
    }

    @Test
    void ignoresEmptyStatementsAndTrailingSemicolons() {
        assertThat(SqlScript.split(";; select 1;; select 2;  "))
                .containsExactly("select 1", "select 2");
    }

    @Test
    void ignoresCommentOnlyStatements() {
        assertThat(SqlScript.split("-- leading\n; /* block */; select 1"))
                .containsExactly("select 1");
    }

    @Test
    void keepsSemicolonsInsideQuotedText() {
        assertThat(SqlScript.split(
                        "select ';', \";\", `;`, [;] from t; insert into t values ('a;b')"))
                .containsExactly(
                        "select ';', \";\", `;`, [;] from t",
                        "insert into t values ('a;b')");
    }

    @Test
    void keepsSemicolonsInsideComments() {
        assertThat(SqlScript.split(
                        "select 1 -- ; not a terminator\n; select 2 /* ; neither */; select 3"))
                .containsExactly("select 1 -- ; not a terminator", "select 2 /* ; neither */", "select 3");
    }

    @Test
    void keepsATriggerBodyAsOneStatement() {
        String trigger = "create trigger t_after_insert after insert on t begin"
                + " update summary set count = count + 1;"
                + " insert into audit (value) values (new.value);"
                + " end";
        assertThat(SqlScript.split(trigger + "; select 1"))
                .containsExactly(trigger, "select 1");
    }

    @Test
    void keepsCaseEndInsideATriggerBody() {
        String trigger = "create temp trigger t_guard before insert on t"
                + " when case when new.value is null then 1 else 0 end"
                + " begin"
                + " select case when new.value is null then raise(abort, 'value') end;"
                + " end";
        assertThat(SqlScript.split(trigger + "; select 1"))
                .containsExactly(trigger, "select 1");
    }

    @Test
    void doesNotTreatABeginInAnOrdinaryStatementAsATriggerBody() {
        assertThat(SqlScript.split("begin; select 1; commit"))
                .containsExactly("begin", "select 1", "commit");
    }

    @Test
    void handlesUnterminatedQuotesWithoutThrowing() {
        assertThat(SqlScript.split("select 'unterminated; select 2"))
                .containsExactly("select 'unterminated; select 2");
    }
}
