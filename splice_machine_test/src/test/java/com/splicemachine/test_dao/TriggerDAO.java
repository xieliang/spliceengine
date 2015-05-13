package com.splicemachine.test_dao;

import org.junit.Assert;

import java.sql.Connection;
import java.util.List;

/**
 * Query sys.systriggers.
 */
public class TriggerDAO {

    private JDBCTemplate jdbcTemplate;

    public TriggerDAO(Connection connection) {
        this.jdbcTemplate = new JDBCTemplate(connection);
    }

    /**
     * Count number of defined triggers with the specified name.
     */
    public long count(String triggerName) {
        List<Long> count = jdbcTemplate.query("" +
                "select count(*) from sys.systriggers t " +
                "join sys.sysschemas s on s.schemaid=t.schemaid " +
                "where triggername=? and schemaname=CURRENT SCHEMA", triggerName.toUpperCase());
        return count.get(0);
    }

    /**
     * Throws assertion error if the specified trigger does not exist in the current schema.
     */
    public void assertTriggerExists(String... triggerNames) {
        for (String triggerName : triggerNames) {
            Assert.assertTrue("expected trigger to exist = " + triggerName, count(triggerName) == 1);
        }
    }

    /**
     * Throws assertion error if the specified trigger does not exist in the current schema.
     */
    public void assertTriggerGone(String... triggerNames) {
        for (String triggerName : triggerNames) {
            Assert.assertTrue("expected trigger NOT to exist = " + triggerName, count(triggerName) == 0);
        }
    }

    /**
     * Drop the specified triggers.
     */
    public void drop(String... triggerNames) {
        for (String triggerName : triggerNames) {
            jdbcTemplate.executeUpdate("DROP TRIGGER " + triggerName);
        }
    }

}