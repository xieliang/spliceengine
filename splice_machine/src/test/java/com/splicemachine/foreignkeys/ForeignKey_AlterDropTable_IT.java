/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.foreignkeys;

import com.splicemachine.derby.test.framework.RuledConnection;
import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.test.SerialTest;
import org.junit.*;
import org.junit.experimental.categories.Category;

import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Foreign key alter table tests.
 *
 * FKs added by alter table is a significant distinct case because when a FK is added by alter table the four FK
 * WriteHandlers have to be added to write contexts (which may already be initialised) on all nodes for both the parent
 * and child tables.  On the other hand when a FK constraint is created as part of a create table statement the write
 * context, for the child at least, does not already exist, and the FK WriteHandlers get created from DB metadata the
 * first time someone writes to the table.
 *
 * Also contains drop table tests.
 */
// SPLICE-894 Remove Serial
@Category(value = {SerialTest.class})
public class ForeignKey_AlterDropTable_IT {

    private static final String SCHEMA = ForeignKey_AlterDropTable_IT.class.getSimpleName();

    @ClassRule
    public static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(SCHEMA);

    @Rule public RuledConnection conn = new RuledConnection(SCHEMA,false);

    @Before
    public void deleteTables() throws Exception {
        try(Statement s = conn.createStatement()){
            s.executeUpdate("drop table if exists C");
            s.executeUpdate("drop table if exists P");
        }
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // alter table tests
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void foreignKeyAddedAfterParentAndChildWriteContextsInitializedIsEnforced() throws Exception {
        // given -- parent table with initialized write context
        try(Statement s = conn.createStatement()){
            s.executeUpdate("create table P(a int primary key)");
            s.executeUpdate("insert into P values(1),(2),(3)");
            // given -- child table with initialized write context
            s.executeUpdate("create table C(a int primary key)");
            s.executeUpdate("insert into C values(1)");

            // when -- we add the foreign key after the write contexts are initialized
            s.executeUpdate("alter table C add constraint FK1 foreign key (a) references P(a)");

            // then -- the foreign key constraint is still enforced
            assertQueryFail("delete from P where a=1","Operation on table 'P' caused a violation of foreign key constraint 'FK1' for key (A).  The statement has been rolled back.");
            assertQueryFail("insert into C values(222)","Operation on table 'C' caused a violation of foreign key constraint 'FK1' for key (A).  The statement has been rolled back.");
            // then -- we can still insert values into the child that do exist in parent
            s.executeUpdate("insert into C values(2)");
            // then -- we can still delete unreferenced values from the parent
            s.executeUpdate("delete from P where a=3");
        }
    }

    @Test
    public void foreignKeyCannotBeAddedIfChildHasMissingPK() throws Exception {
        //regression test for DB-4415. Set the connection schema to SPLICE, then manually reference the schema
        conn.setSchema("SPLICE");
        // given -- parent table with initialized write context
        try(Statement s = conn.createStatement()){
            s.executeUpdate("create table "+SCHEMA+".P(a int, CONSTRAINT PK primary key(a) )");
            s.executeUpdate("insert into "+SCHEMA+".P values(2),(3)");
            // given -- child table with initialized write context
            s.executeUpdate("create table "+SCHEMA+".C(a int primary key)");
            s.executeUpdate("insert into "+SCHEMA+".C values(1)");

            // when -- we add the foreign key after the write contexts are initialized
            String alterSql = "alter table "+SCHEMA+".C add constraint FK_1 foreign key (a) references "+SCHEMA+".P(a)";
            String expectedError = "Foreign key constraint 'FK_1' cannot be added to or enabled on table " +
                    "\"FOREIGNKEY_ALTERDROPTABLE_IT\".\"C\" because one or more foreign keys do not have matching referenced keys.";
            assertQueryFail(alterSql,expectedError);

            // then -- the foreign key constraint is NOT enforced
            s.executeUpdate("delete from "+SCHEMA+".P where a=1");
        }finally{
            //reset the schema
            conn.setSchema(SCHEMA.toUpperCase());
        }
    }

    @Test
    public void alterTable_removesFkConstraint() throws Exception {
        try(Statement s = conn.createStatement()){
            // given -- C -> P with values in both
            s.executeUpdate("create table P (a int, b int, constraint pk1 primary key(a))");
            s.executeUpdate("create table C (a int, CONSTRAINT fk1 FOREIGN KEY(a) REFERENCES P(a))");
            s.executeUpdate("insert into P values(1,1)");
            s.executeUpdate("insert into C values(1)");

            // given -- we can't delete from P because of the FK constraint
            assertQueryFail("delete from P where a =1","Operation on table 'P' caused a violation of foreign key constraint 'FK1' for key (A).  The statement has been rolled back.");

            // when -- we drop the FK constraint
            s.executeUpdate("alter table C drop constraint FK1");

            // then -- we should be able to delete from P
            assertEquals(1,s.executeUpdate("delete from P where a =1"));
            // then -- we should be able to insert non matching values into C
            assertEquals(1,s.executeUpdate("insert into C values(999)"));
        }
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // drop table tests
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void dropTable_failsIfTableWithDependentFKExists() throws Exception {
        try(Statement s = conn.createStatement()){
            s.executeUpdate("create table P (a int, b int, constraint pk1 primary key(a))");
            s.executeUpdate("create table C (a int, CONSTRAINT fk1 FOREIGN KEY(a) REFERENCES P(a))");

            assertQueryFail("drop table P","Operation 'DROP CONSTRAINT' cannot be performed on object 'PK1' because CONSTRAINT 'FK1' is dependent on that object.");

            // This order should succeed.
            s.executeUpdate("drop table C");
            s.executeUpdate("drop table P");
        }
    }

    @Test
    public void dropTable_removesFkConstraint() throws Exception {
        try(Statement s = conn.createStatement()){
            // given -- C -> P with values in both
            s.executeUpdate("create table P (a int, b int, constraint pk1 primary key(a))");
            s.executeUpdate("create table C (a int, CONSTRAINT fk1 FOREIGN KEY(a) REFERENCES P(a))");
            s.executeUpdate("insert into P values(1,1)");
            s.executeUpdate("insert into C values(1)");

            // given -- we can't delete from P because of the FK constraint
            assertQueryFail("delete from P where a =1","Operation on table 'P' caused a violation of foreign key constraint 'FK1' for key (A).  The statement has been rolled back.");

            // when -- we drop C
            s.executeUpdate("drop table C");

            // then -- we should be able to delete from P
            assertEquals(1,s.executeUpdate("delete from P where a =1"));
        }
    }

    @Test
    public void onDeleteCascadeThrowsError() throws Exception{
        //Regression test for DB-3985. Make sure that ON DELETE CASCADE create table statements explode. Remove this when DB-2224 is implemented
        try(Statement s = conn.createStatement()){
            s.executeUpdate("create table P (a int primary key, b int)");

            assertQueryFail("create table C (a int references P(a) ON DELETE CASCADE)","Feature not implemented: ON DELETE CASCADE.");
        }
    }

    @Test
    public void onDeleteSetNullThrowsError() throws Exception{
        //Regression test for DB-3985. Make sure that ON DELETE CASCADE create table statements explode. Remove this when DB-2224 is implemented
        try(Statement s = conn.createStatement()){
            s.executeUpdate("create table P (a int primary key, b int)");

            assertQueryFail("create table C (a int references P(a) ON DELETE SET NULL)","Feature not implemented: ON DELETE SET NULL.");
        }
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // helper methods
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    private void assertQueryFail(String sql, String expectedExceptionMessage) {
        try(Statement s = conn.createStatement()) {
            s.executeUpdate(sql);
            fail(String.format("Expected query '%s' to fail with error message '%s'", sql, expectedExceptionMessage));
        } catch (Exception e) {
            assertEquals(expectedExceptionMessage, e.getMessage());
        }
    }

}