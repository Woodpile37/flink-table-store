/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.testutils.assertj.AssertionUtils;
import org.apache.paimon.utils.BlockingIterator;

import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.flink.table.planner.factories.TestValuesTableFactory.changelogRow;
import static org.apache.paimon.CoreOptions.CHANGELOG_PRODUCER;
import static org.apache.paimon.flink.action.MergeIntoActionFactory.MATCHED_DELETE;
import static org.apache.paimon.flink.action.MergeIntoActionFactory.MATCHED_UPSERT;
import static org.apache.paimon.flink.action.MergeIntoActionFactory.NOT_MATCHED_BY_SOURCE_DELETE;
import static org.apache.paimon.flink.action.MergeIntoActionFactory.NOT_MATCHED_BY_SOURCE_UPSERT;
import static org.apache.paimon.flink.action.MergeIntoActionFactory.NOT_MATCHED_INSERT;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.bEnv;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.buildDdl;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.buildSimpleQuery;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.createTable;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.init;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.insertInto;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.sEnv;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.testBatchRead;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.testStreamingRead;
import static org.apache.paimon.flink.util.ReadWriteTableTestUtil.validateStreamingReadResult;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** IT cases for {@link MergeIntoAction}. */
public class MergeIntoActionITCase extends ActionITCaseBase {

    private static final List<Row> initialRecords =
            Arrays.asList(
                    changelogRow("+I", 1, "v_1", "creation", "02-27"),
                    changelogRow("+I", 2, "v_2", "creation", "02-27"),
                    changelogRow("+I", 3, "v_3", "creation", "02-27"),
                    changelogRow("+I", 4, "v_4", "creation", "02-27"),
                    changelogRow("+I", 5, "v_5", "creation", "02-28"),
                    changelogRow("+I", 6, "v_6", "creation", "02-28"),
                    changelogRow("+I", 7, "v_7", "creation", "02-28"),
                    changelogRow("+I", 8, "v_8", "creation", "02-28"),
                    changelogRow("+I", 9, "v_9", "creation", "02-28"),
                    changelogRow("+I", 10, "v_10", "creation", "02-28"));

    @BeforeEach
    public void setUp() throws Exception {
        init(warehouse);

        // prepare target table T
        prepareTargetTable(CoreOptions.ChangelogProducer.NONE);

        // prepare source table S
        prepareSourceTable();
    }

    @ParameterizedTest(name = "changelog-producer = {0}")
    @MethodSource("producerTestData")
    public void testVariousChangelogProducer(
            CoreOptions.ChangelogProducer producer, List<Row> expected) throws Exception {
        // re-create target table with given producer
        sEnv.executeSql("DROP TABLE T");
        prepareTargetTable(producer);

        // similar to:
        // MERGE INTO T
        // USING S
        // ON T.k = S.k AND T.dt = S.dt
        // WHEN MATCHED AND (T.v <> S.v AND S.v IS NOT NULL) THEN UPDATE
        //   SET v = S.v, last_action = 'matched_upsert'
        // WHEN MATCHED AND S.v IS NULL THEN DELETE
        // WHEN NOT MATCHED THEN INSERT VALUES (S.k, S.v, 'insert', S.dt)
        // WHEN NOT MATCHED BY SOURCE AND (dt < '02-28') THEN UPDATE
        //   SET v = v || '_nmu', last_action = 'not_matched_upsert'
        // WHEN NOT MATCHED BY SOURCE AND (dt >= '02-28') THEN DELETE
        MergeIntoAction action = new MergeIntoAction(warehouse, database, "T");
        // here test if it works when table S is in default and qualified both
        action.withSourceTable("default.S")
                .withMergeCondition("T.k = S.k AND T.dt = S.dt")
                .withMatchedUpsert(
                        "T.v <> S.v AND S.v IS NOT NULL", "v = S.v, last_action = 'matched_upsert'")
                .withMatchedDelete("S.v IS NULL")
                .withNotMatchedInsert(null, "S.k, S.v, 'insert', S.dt")
                .withNotMatchedBySourceUpsert(
                        "dt < '02-28'", "v = v || '_nmu', last_action = 'not_matched_upsert'")
                .withNotMatchedBySourceDelete("dt >= '02-28'");

        String mergeActions =
                String.format(
                        "%s,%s,%s,%s,%s",
                        MATCHED_UPSERT,
                        MATCHED_DELETE,
                        NOT_MATCHED_INSERT,
                        NOT_MATCHED_BY_SOURCE_UPSERT,
                        NOT_MATCHED_BY_SOURCE_DELETE);
        String procedureStatement =
                String.format(
                        "CALL merge_into('%s.T', '', '', 'default.S', 'T.k = S.k AND T.dt = S.dt', '%s', %s)",
                        database,
                        mergeActions,
                        "'T.v <> S.v AND S.v IS NOT NULL', "
                                + "'v = S.v, last_action = ''matched_upsert''', "
                                + "'S.v IS NULL', "
                                + "'', "
                                + "'S.k, S.v, ''insert'', S.dt', "
                                + "'dt < ''02-28''', "
                                + "'v = v || ''_nmu'', last_action = ''not_matched_upsert''', "
                                + "'dt >= ''02-28'''");

        validateActionRunResult(
                action,
                procedureStatement,
                expected,
                Arrays.asList(
                        changelogRow("+I", 1, "v_1", "creation", "02-27"),
                        changelogRow("+U", 2, "v_2_nmu", "not_matched_upsert", "02-27"),
                        changelogRow("+U", 3, "v_3_nmu", "not_matched_upsert", "02-27"),
                        changelogRow("+U", 7, "Seven", "matched_upsert", "02-28"),
                        changelogRow("+I", 8, "v_8", "insert", "02-29"),
                        changelogRow("+I", 11, "v_11", "insert", "02-29"),
                        changelogRow("+I", 12, "v_12", "insert", "02-29")));

        if (producer == CoreOptions.ChangelogProducer.FULL_COMPACTION) {
            // test partial update still works after action
            testWorkWithPartialUpdate();
        }
    }

    private void testWorkWithPartialUpdate() throws Exception {
        insertInto(
                "T",
                "(12, CAST (NULL AS STRING), '$', '02-29')",
                "(12, 'Test', CAST (NULL AS STRING), '02-29')");

        testBatchRead(
                buildSimpleQuery("T"),
                Arrays.asList(
                        changelogRow("+I", 1, "v_1", "creation", "02-27"),
                        changelogRow("+U", 2, "v_2_nmu", "not_matched_upsert", "02-27"),
                        changelogRow("+U", 3, "v_3_nmu", "not_matched_upsert", "02-27"),
                        changelogRow("+U", 7, "Seven", "matched_upsert", "02-28"),
                        changelogRow("+I", 8, "v_8", "insert", "02-29"),
                        changelogRow("+I", 11, "v_11", "insert", "02-29"),
                        changelogRow("+I", 12, "Test", "$", "02-29")));
    }

    @ParameterizedTest(name = "in-default = {0}")
    @ValueSource(booleans = {true, false})
    public void testTargetAlias(boolean inDefault) throws Exception {
        MergeIntoAction action;

        if (!inDefault) {
            // create target table in a new database
            sEnv.executeSql("DROP TABLE T");
            sEnv.executeSql("CREATE DATABASE test_db");
            sEnv.executeSql("USE test_db");
            bEnv.executeSql("USE test_db");
            prepareTargetTable(CoreOptions.ChangelogProducer.NONE);

            action = new MergeIntoAction(warehouse, "test_db", "T");
        } else {
            action = new MergeIntoAction(warehouse, database, "T");
        }

        action.withTargetAlias("TT")
                .withSourceTable("S")
                .withMergeCondition("TT.k = S.k AND TT.dt = S.dt")
                .withMatchedDelete("S.v IS NULL");

        String procedureStatement =
                String.format(
                        "CALL merge_into('%s.T', 'TT', '', 'S', 'TT.k = S.k AND TT.dt = S.dt', '%s', %s)",
                        inDefault ? database : "test_db", MATCHED_DELETE, "'S.v IS NULL'");

        validateActionRunResult(
                action,
                procedureStatement,
                Arrays.asList(
                        changelogRow("-D", 4, "v_4", "creation", "02-27"),
                        changelogRow("-D", 8, "v_8", "creation", "02-28")),
                Arrays.asList(
                        changelogRow("+I", 1, "v_1", "creation", "02-27"),
                        changelogRow("+I", 2, "v_2", "creation", "02-27"),
                        changelogRow("+I", 3, "v_3", "creation", "02-27"),
                        changelogRow("+I", 5, "v_5", "creation", "02-28"),
                        changelogRow("+I", 6, "v_6", "creation", "02-28"),
                        changelogRow("+I", 7, "v_7", "creation", "02-28"),
                        changelogRow("+I", 9, "v_9", "creation", "02-28"),
                        changelogRow("+I", 10, "v_10", "creation", "02-28")));
    }

    @ParameterizedTest(name = "in-default = {0}")
    @ValueSource(booleans = {true, false})
    public void testSourceName(boolean inDefault) throws Exception {
        MergeIntoAction action = new MergeIntoAction(warehouse, "default", "T");
        String sourceTableName = "S";

        if (!inDefault) {
            // create source table in a new database
            sEnv.executeSql("DROP TABLE S");
            sEnv.executeSql("CREATE DATABASE test_db");
            sEnv.executeSql("USE test_db");
            bEnv.executeSql("USE test_db");
            prepareSourceTable();
            sourceTableName = "test_db.S";
        }

        action.withSourceTable(sourceTableName)
                .withMergeCondition("T.k = S.k AND T.dt = S.dt")
                .withMatchedDelete("S.v IS NULL");

        String procedureStatement =
                String.format(
                        "CALL merge_into('default.T', '', '', '%s', 'T.k = S.k AND T.dt = S.dt', '%s', %s)",
                        sourceTableName, MATCHED_DELETE, "'S.v IS NULL'");

        if (!inDefault) {
            sEnv.executeSql("USE `default`");
            bEnv.executeSql("USE `default`");
        }

        validateActionRunResult(
                action,
                procedureStatement,
                Arrays.asList(
                        changelogRow("-D", 4, "v_4", "creation", "02-27"),
                        changelogRow("-D", 8, "v_8", "creation", "02-28")),
                Arrays.asList(
                        changelogRow("+I", 1, "v_1", "creation", "02-27"),
                        changelogRow("+I", 2, "v_2", "creation", "02-27"),
                        changelogRow("+I", 3, "v_3", "creation", "02-27"),
                        changelogRow("+I", 5, "v_5", "creation", "02-28"),
                        changelogRow("+I", 6, "v_6", "creation", "02-28"),
                        changelogRow("+I", 7, "v_7", "creation", "02-28"),
                        changelogRow("+I", 9, "v_9", "creation", "02-28"),
                        changelogRow("+I", 10, "v_10", "creation", "02-28")));
    }

    @ParameterizedTest(name = "useCatalog = {0}")
    @ValueSource(booleans = {true, false})
    public void testSqls(boolean useCatalog) throws Exception {
        // drop table S
        sEnv.executeSql("DROP TABLE S");

        String catalog =
                String.format(
                        "CREATE CATALOG test_cat WITH ('type' = 'paimon', 'warehouse' = '%s')",
                        getTempDirPath());
        String escapeCatalog = catalog.replaceAll("'", "''");
        String id =
                TestValuesTableFactory.registerData(
                        Arrays.asList(
                                changelogRow("+I", 1, "v_1", "02-27"),
                                changelogRow("+I", 4, null, "02-27"),
                                changelogRow("+I", 8, null, "02-28")));
        String ddl =
                String.format(
                        "CREATE TEMPORARY TABLE %s (k INT, v STRING, dt STRING)\n"
                                + "WITH ('connector' = 'values', 'bounded' = 'true', 'data-id' = '%s')",
                        useCatalog ? "S" : "test_cat.`default`.S", id);
        String escapeDdl = ddl.replaceAll("'", "''");

        MergeIntoAction action = new MergeIntoAction(warehouse, database, "T");

        if (useCatalog) {
            action.withSourceSqls(catalog, "USE CATALOG test_cat", ddl);
            // test current catalog and current database
            action.withSourceTable("S");
        } else {
            action.withSourceSqls(catalog, ddl);
            action.withSourceTable("test_cat.default.S");
        }

        action.withMergeCondition("T.k = S.k AND T.dt = S.dt").withMatchedDelete("S.v IS NULL");

        String procedureStatement =
                String.format(
                        "CALL merge_into('%s.T', '', '%s', '%s', 'T.k = S.k AND T.dt = S.dt', '%s', %s)",
                        database,
                        useCatalog
                                ? String.format(
                                        "%s;%s;%s",
                                        escapeCatalog, "USE CATALOG test_cat", escapeDdl)
                                : String.format("%s;%s", escapeCatalog, escapeDdl),
                        useCatalog ? "S" : "test_cat.default.S",
                        MATCHED_DELETE,
                        "'S.v IS NULL'");

        validateActionRunResult(
                action,
                procedureStatement,
                Arrays.asList(
                        changelogRow("-D", 4, "v_4", "creation", "02-27"),
                        changelogRow("-D", 8, "v_8", "creation", "02-28")),
                Arrays.asList(
                        changelogRow("+I", 1, "v_1", "creation", "02-27"),
                        changelogRow("+I", 2, "v_2", "creation", "02-27"),
                        changelogRow("+I", 3, "v_3", "creation", "02-27"),
                        changelogRow("+I", 5, "v_5", "creation", "02-28"),
                        changelogRow("+I", 6, "v_6", "creation", "02-28"),
                        changelogRow("+I", 7, "v_7", "creation", "02-28"),
                        changelogRow("+I", 9, "v_9", "creation", "02-28"),
                        changelogRow("+I", 10, "v_10", "creation", "02-28")));
    }

    @ParameterizedTest(name = "source-qualified = {0}")
    @ValueSource(booleans = {true, false})
    public void testMatchedUpsertSetAll(boolean qualified) throws Exception {
        // build MergeIntoAction
        MergeIntoAction action = new MergeIntoAction(warehouse, database, "T");
        action.withSourceSqls("CREATE TEMPORARY VIEW SS AS SELECT k, v, 'unknown', dt FROM S")
                .withSourceTable(qualified ? "default.SS" : "SS")
                .withMergeCondition("T.k = SS.k AND T.dt = SS.dt")
                .withMatchedUpsert(null, "*");

        String procedureStatement =
                String.format(
                        "CALL merge_into('%s.T', '', '%s', '%s', 'T.k = SS.k AND T.dt = SS.dt', '%s', %s)",
                        database,
                        "CREATE TEMPORARY VIEW SS AS SELECT k, v, ''unknown'', dt FROM S",
                        qualified ? "default.SS" : "SS",
                        MATCHED_UPSERT,
                        "'', '*'");

        validateActionRunResult(
                action,
                procedureStatement,
                Arrays.asList(
                        changelogRow("-U", 1, "v_1", "creation", "02-27"),
                        changelogRow("+U", 1, "v_1", "unknown", "02-27"),
                        changelogRow("-U", 4, "v_4", "creation", "02-27"),
                        changelogRow("+U", 4, null, "unknown", "02-27"),
                        changelogRow("-U", 7, "v_7", "creation", "02-28"),
                        changelogRow("+U", 7, "Seven", "unknown", "02-28"),
                        changelogRow("-U", 8, "v_8", "creation", "02-28"),
                        changelogRow("+U", 8, null, "unknown", "02-28")),
                Arrays.asList(
                        changelogRow("+U", 1, "v_1", "unknown", "02-27"),
                        changelogRow("+I", 2, "v_2", "creation", "02-27"),
                        changelogRow("+I", 3, "v_3", "creation", "02-27"),
                        changelogRow("+U", 4, null, "unknown", "02-27"),
                        changelogRow("+I", 5, "v_5", "creation", "02-28"),
                        changelogRow("+I", 6, "v_6", "creation", "02-28"),
                        changelogRow("+U", 7, "Seven", "unknown", "02-28"),
                        changelogRow("+U", 8, null, "unknown", "02-28"),
                        changelogRow("+I", 9, "v_9", "creation", "02-28"),
                        changelogRow("+I", 10, "v_10", "creation", "02-28")));
    }

    @ParameterizedTest(name = "source-qualified = {0}")
    @ValueSource(booleans = {true, false})
    public void testNotMatchedInsertAll(boolean qualified) throws Exception {
        // build MergeIntoAction
        MergeIntoAction action = new MergeIntoAction(warehouse, database, "T");
        action.withSourceSqls("CREATE TEMPORARY VIEW SS AS SELECT k, v, 'unknown', dt FROM S")
                .withSourceTable(qualified ? "default.SS" : "SS")
                .withMergeCondition("T.k = SS.k AND T.dt = SS.dt")
                .withNotMatchedInsert("SS.k < 12", "*");

        String procedureStatement =
                String.format(
                        "CALL merge_into('%s.T', '', '%s', '%s', 'T.k = SS.k AND T.dt = SS.dt', '%s', %s)",
                        database,
                        "CREATE TEMPORARY VIEW SS AS SELECT k, v, ''unknown'', dt FROM S",
                        qualified ? "default.SS" : "SS",
                        NOT_MATCHED_INSERT,
                        "'SS.k < 12', '*'");

        validateActionRunResult(
                action,
                procedureStatement,
                Arrays.asList(
                        changelogRow("+I", 8, "v_8", "unknown", "02-29"),
                        changelogRow("+I", 11, "v_11", "unknown", "02-29")),
                Arrays.asList(
                        changelogRow("+I", 1, "v_1", "creation", "02-27"),
                        changelogRow("+I", 2, "v_2", "creation", "02-27"),
                        changelogRow("+I", 3, "v_3", "creation", "02-27"),
                        changelogRow("+I", 4, "v_4", "creation", "02-27"),
                        changelogRow("+I", 5, "v_5", "creation", "02-28"),
                        changelogRow("+I", 6, "v_6", "creation", "02-28"),
                        changelogRow("+I", 7, "v_7", "creation", "02-28"),
                        changelogRow("+I", 8, "v_8", "creation", "02-28"),
                        changelogRow("+I", 9, "v_9", "creation", "02-28"),
                        changelogRow("+I", 10, "v_10", "creation", "02-28"),
                        changelogRow("+I", 8, "v_8", "unknown", "02-29"),
                        changelogRow("+I", 11, "v_11", "unknown", "02-29")));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Negative tests
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    public void testNonPkTable() {
        String nonPkTable =
                createTable(
                        Collections.singletonList("k int"),
                        Collections.emptyList(),
                        Collections.emptyList());

        assertThatThrownBy(() -> new MergeIntoAction(warehouse, database, nonPkTable))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(
                        "merge-into action doesn't support table with no primary keys defined.");
    }

    @Test
    public void testIncompatibleSchema() {
        // build MergeIntoAction
        MergeIntoAction action = new MergeIntoAction(warehouse, database, "T");
        action.withSourceTable("S")
                .withMergeCondition("T.k = S.k AND T.dt = S.dt")
                .withNotMatchedInsert(null, "S.k, S.v, 0, S.dt");

        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "The schema of result in action 'not-matched-insert' is invalid.\n"
                                + "Result schema:   [INT NOT NULL, STRING, INT NOT NULL, STRING NOT NULL]\n"
                                + "Expected schema: [INT NOT NULL, STRING, STRING, STRING NOT NULL]");
    }

    @Test
    public void testIllegalSourceName() throws Exception {
        // create source table in a new database
        sEnv.executeSql("DROP TABLE S");
        sEnv.executeSql("CREATE DATABASE test_db");
        sEnv.executeSql("USE test_db");
        prepareSourceTable();

        MergeIntoAction action = new MergeIntoAction(warehouse, "default", "T");
        // the qualified path of source table is absent
        action.withSourceTable("S")
                .withMergeCondition("T.k = S.k AND T.dt = S.dt")
                .withMatchedDelete("S.v IS NULL");

        assertThatThrownBy(action::run)
                .satisfies(
                        AssertionUtils.anyCauseMatches(
                                ValidationException.class, "Object 'S' not found"));
    }

    @Test
    public void testIllegalSourceNameSqlCase() {
        // drop table S
        sEnv.executeSql("DROP TABLE S");

        MergeIntoAction action = new MergeIntoAction(warehouse, "default", "T");
        action.withSourceSqls(
                        "CREATE DATABASE test_db",
                        "CREATE TEMPORARY TABLE test_db.S (k INT, v STRING, dt STRING) WITH ('connector' = 'values', 'bounded' = 'true')")
                // the qualified path of source table  is absent
                .withSourceTable("S")
                .withMergeCondition("T.k = S.k AND T.dt = S.dt")
                .withMatchedDelete("S.v IS NULL");

        assertThatThrownBy(action::run)
                .satisfies(
                        AssertionUtils.anyCauseMatches(
                                ValidationException.class, "Object 'S' not found"));
    }

    private void validateActionRunResult(
            MergeIntoAction action,
            String procedureStatement,
            List<Row> streamingExpected,
            List<Row> batchExpected)
            throws Exception {
        BlockingIterator<Row, Row> iterator =
                testStreamingRead(buildSimpleQuery("T"), initialRecords);
        if (ThreadLocalRandom.current().nextBoolean()) {
            action.run();
        } else {
            callProcedure(procedureStatement);
        }
        // test streaming read
        validateStreamingReadResult(iterator, streamingExpected);
        iterator.close();
        // test batch read
        testBatchRead(buildSimpleQuery("T"), batchExpected);
    }

    private void prepareTargetTable(CoreOptions.ChangelogProducer producer) throws Exception {
        sEnv.executeSql(
                buildDdl(
                        "T",
                        Arrays.asList("k INT", "v STRING", "last_action STRING", "dt STRING"),
                        Arrays.asList("k", "dt"),
                        Collections.singletonList("dt"),
                        new HashMap<String, String>() {
                            {
                                put(CHANGELOG_PRODUCER.key(), producer.toString());
                                // test works with partial update normally
                                if (producer == CoreOptions.ChangelogProducer.FULL_COMPACTION) {
                                    put(
                                            CoreOptions.MERGE_ENGINE.key(),
                                            CoreOptions.MergeEngine.PARTIAL_UPDATE.toString());
                                    put(CoreOptions.PARTIAL_UPDATE_IGNORE_DELETE.key(), "true");
                                }
                            }
                        }));

        insertInto(
                "T",
                "(1, 'v_1', 'creation', '02-27')",
                "(2, 'v_2', 'creation', '02-27')",
                "(3, 'v_3', 'creation', '02-27')",
                "(4, 'v_4', 'creation', '02-27')",
                "(5, 'v_5', 'creation', '02-28')",
                "(6, 'v_6', 'creation', '02-28')",
                "(7, 'v_7', 'creation', '02-28')",
                "(8, 'v_8', 'creation', '02-28')",
                "(9, 'v_9', 'creation', '02-28')",
                "(10, 'v_10', 'creation', '02-28')");
    }

    private void prepareSourceTable() throws Exception {
        sEnv.executeSql(
                buildDdl(
                        "S",
                        Arrays.asList("k INT", "v STRING", "dt STRING"),
                        Arrays.asList("k", "dt"),
                        Collections.singletonList("dt"),
                        new HashMap<>()));

        insertInto(
                "S",
                "(1, 'v_1', '02-27')",
                "(4, CAST (NULL AS STRING), '02-27')",
                "(7, 'Seven', '02-28')",
                "(8, CAST (NULL AS STRING), '02-28')",
                "(8, 'v_8', '02-29')",
                "(11, 'v_11', '02-29')",
                "(12, 'v_12', '02-29')");
    }

    private static List<Arguments> producerTestData() {
        return Arrays.asList(
                arguments(
                        CoreOptions.ChangelogProducer.NONE,
                        Arrays.asList(
                                changelogRow("-U", 7, "v_7", "creation", "02-28"),
                                changelogRow("+U", 7, "Seven", "matched_upsert", "02-28"),
                                changelogRow("-D", 4, "v_4", "creation", "02-27"),
                                changelogRow("-D", 8, "v_8", "creation", "02-28"),
                                changelogRow("+I", 8, "v_8", "insert", "02-29"),
                                changelogRow("+I", 11, "v_11", "insert", "02-29"),
                                changelogRow("+I", 12, "v_12", "insert", "02-29"),
                                changelogRow("-U", 2, "v_2", "creation", "02-27"),
                                changelogRow("+U", 2, "v_2_nmu", "not_matched_upsert", "02-27"),
                                changelogRow("-U", 3, "v_3", "creation", "02-27"),
                                changelogRow("+U", 3, "v_3_nmu", "not_matched_upsert", "02-27"),
                                changelogRow("-D", 5, "v_5", "creation", "02-28"),
                                changelogRow("-D", 6, "v_6", "creation", "02-28"),
                                changelogRow("-D", 9, "v_9", "creation", "02-28"),
                                changelogRow("-D", 10, "v_10", "creation", "02-28"))),
                arguments(
                        CoreOptions.ChangelogProducer.INPUT,
                        Arrays.asList(
                                changelogRow("+U", 7, "Seven", "matched_upsert", "02-28"),
                                changelogRow("-D", 4, "v_4", "creation", "02-27"),
                                changelogRow("-D", 8, "v_8", "creation", "02-28"),
                                changelogRow("+I", 8, "v_8", "insert", "02-29"),
                                changelogRow("+I", 11, "v_11", "insert", "02-29"),
                                changelogRow("+I", 12, "v_12", "insert", "02-29"),
                                changelogRow("+U", 2, "v_2_nmu", "not_matched_upsert", "02-27"),
                                changelogRow("+U", 3, "v_3_nmu", "not_matched_upsert", "02-27"),
                                changelogRow("-D", 5, "v_5", "creation", "02-28"),
                                changelogRow("-D", 6, "v_6", "creation", "02-28"),
                                changelogRow("-D", 9, "v_9", "creation", "02-28"),
                                changelogRow("-D", 10, "v_10", "creation", "02-28"))),
                arguments(
                        CoreOptions.ChangelogProducer.FULL_COMPACTION,
                        Arrays.asList(
                                changelogRow("-U", 7, "v_7", "creation", "02-28"),
                                changelogRow("+U", 7, "Seven", "matched_upsert", "02-28"),
                                changelogRow("-D", 4, "v_4", "creation", "02-27"),
                                changelogRow("-D", 8, "v_8", "creation", "02-28"),
                                changelogRow("+I", 8, "v_8", "insert", "02-29"),
                                changelogRow("+I", 11, "v_11", "insert", "02-29"),
                                changelogRow("+I", 12, "v_12", "insert", "02-29"),
                                changelogRow("-U", 2, "v_2", "creation", "02-27"),
                                changelogRow("+U", 2, "v_2_nmu", "not_matched_upsert", "02-27"),
                                changelogRow("-U", 3, "v_3", "creation", "02-27"),
                                changelogRow("+U", 3, "v_3_nmu", "not_matched_upsert", "02-27"),
                                changelogRow("-D", 5, "v_5", "creation", "02-28"),
                                changelogRow("-D", 6, "v_6", "creation", "02-28"),
                                changelogRow("-D", 9, "v_9", "creation", "02-28"),
                                changelogRow("-D", 10, "v_10", "creation", "02-28"))));
    }
}
