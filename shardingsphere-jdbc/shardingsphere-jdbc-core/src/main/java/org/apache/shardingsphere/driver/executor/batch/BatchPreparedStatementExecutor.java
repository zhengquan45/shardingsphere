/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.driver.executor.batch;

import com.google.common.base.Preconditions;
import lombok.Getter;
import org.apache.shardingsphere.infra.context.schema.SchemaContexts;
import org.apache.shardingsphere.infra.executor.kernel.InputGroup;
import org.apache.shardingsphere.infra.executor.sql.ConnectionMode;
import org.apache.shardingsphere.infra.executor.sql.context.ExecutionUnit;
import org.apache.shardingsphere.infra.executor.sql.resourced.jdbc.StatementExecuteUnit;
import org.apache.shardingsphere.infra.executor.sql.resourced.jdbc.executor.ExecutorExceptionHandler;
import org.apache.shardingsphere.infra.executor.sql.resourced.jdbc.executor.SQLExecutor;
import org.apache.shardingsphere.infra.executor.sql.resourced.jdbc.executor.SQLExecutorCallback;
import org.apache.shardingsphere.infra.executor.sql.resourced.jdbc.executor.impl.DefaultSQLExecutorCallback;
import org.apache.shardingsphere.infra.rule.DataNodeRoutedRule;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Prepared statement executor to process add batch.
 */
public final class BatchPreparedStatementExecutor {
    
    private final SchemaContexts schemaContexts;
    
    private final SQLExecutor sqlExecutor;
    
    private final Collection<InputGroup<StatementExecuteUnit>> inputGroups;
    
    @Getter
    private final Collection<BatchExecutionUnit> batchExecutionUnits;
    
    private int batchCount;
    
    public BatchPreparedStatementExecutor(final SchemaContexts schemaContexts, final SQLExecutor sqlExecutor) {
        this.schemaContexts = schemaContexts;
        this.sqlExecutor = sqlExecutor;
        inputGroups = new LinkedList<>();
        batchExecutionUnits = new LinkedList<>();
    }
    
    /**
     * Initialize executor.
     *
     * @param inputGroups input groups
     */
    public void init(final Collection<InputGroup<StatementExecuteUnit>> inputGroups) {
        this.inputGroups.addAll(inputGroups);
    }
    
    /**
     * Add batch for execution units.
     *
     * @param executionUnits execution units
     */
    public void addBatchForExecutionUnits(final Collection<ExecutionUnit> executionUnits) {
        Collection<BatchExecutionUnit> batchExecutionUnits = createBatchExecutionUnits(executionUnits);
        handleOldBatchExecutionUnits(batchExecutionUnits);
        handleNewBatchExecutionUnits(batchExecutionUnits);
        batchCount++;
    }
    
    private Collection<BatchExecutionUnit> createBatchExecutionUnits(final Collection<ExecutionUnit> executionUnits) {
        return executionUnits.stream().map(BatchExecutionUnit::new).collect(Collectors.toList());
    }
    
    private void handleOldBatchExecutionUnits(final Collection<BatchExecutionUnit> newExecutionUnits) {
        newExecutionUnits.forEach(this::reviseBatchExecutionUnits);
    }
    
    private void reviseBatchExecutionUnits(final BatchExecutionUnit batchExecutionUnit) {
        batchExecutionUnits.stream().filter(each -> each.equals(batchExecutionUnit)).forEach(each -> reviseBatchExecutionUnit(each, batchExecutionUnit));
    }
    
    private void reviseBatchExecutionUnit(final BatchExecutionUnit oldBatchExecutionUnit, final BatchExecutionUnit newBatchExecutionUnit) {
        oldBatchExecutionUnit.getExecutionUnit().getSqlUnit().getParameters().addAll(newBatchExecutionUnit.getExecutionUnit().getSqlUnit().getParameters());
        oldBatchExecutionUnit.mapAddBatchCount(batchCount);
    }
    
    private void handleNewBatchExecutionUnits(final Collection<BatchExecutionUnit> newExecutionUnits) {
        newExecutionUnits.removeAll(batchExecutionUnits);
        newExecutionUnits.forEach(each -> each.mapAddBatchCount(batchCount));
        batchExecutionUnits.addAll(newExecutionUnits);
    }
    
    /**
     * Execute batch.
     *
     * @param sqlStatementContext SQL statement context
     * @return execute results
     * @throws SQLException SQL exception
     */
    public int[] executeBatch(final SQLStatementContext sqlStatementContext) throws SQLException {
        boolean isExceptionThrown = ExecutorExceptionHandler.isExceptionThrown();
        SQLExecutorCallback<int[]> callback = new DefaultSQLExecutorCallback<int[]>(schemaContexts.getDatabaseType(), isExceptionThrown) {
            
            @Override
            protected int[] executeSQL(final String sql, final Statement statement, final ConnectionMode connectionMode) throws SQLException {
                return statement.executeBatch();
            }
        };
        List<int[]> results = sqlExecutor.execute(inputGroups, callback);
        return isNeedAccumulate(
                schemaContexts.getDefaultSchema().getRules().stream().filter(rule -> rule instanceof DataNodeRoutedRule).collect(Collectors.toList()), sqlStatementContext)
                ? accumulate(results) : results.get(0);
    }
    
    private boolean isNeedAccumulate(final Collection<ShardingSphereRule> rules, final SQLStatementContext sqlStatementContext) {
        return rules.stream().anyMatch(each -> ((DataNodeRoutedRule) each).isNeedAccumulate(sqlStatementContext.getTablesContext().getTableNames()));
    }
    
    private int[] accumulate(final List<int[]> results) {
        int[] result = new int[batchCount];
        int count = 0;
        for (InputGroup<StatementExecuteUnit> each : inputGroups) {
            for (StatementExecuteUnit eachUnit : each.getInputs()) {
                Map<Integer, Integer> jdbcAndActualAddBatchCallTimesMap = Collections.emptyMap();
                for (BatchExecutionUnit eachExecutionUnit : batchExecutionUnits) {
                    if (isSameDataSourceAndSQL(eachExecutionUnit, eachUnit)) {
                        jdbcAndActualAddBatchCallTimesMap = eachExecutionUnit.getJdbcAndActualAddBatchCallTimesMap();
                        break;
                    }
                }
                for (Entry<Integer, Integer> entry : jdbcAndActualAddBatchCallTimesMap.entrySet()) {
                    int value = null == results.get(count) ? 0 : results.get(count)[entry.getValue()];
                    result[entry.getKey()] += value;
                }
                count++;
            }
        }
        return result;
    }
    
    private boolean isSameDataSourceAndSQL(final BatchExecutionUnit batchExecutionUnit, final StatementExecuteUnit statementExecuteUnit) {
        return batchExecutionUnit.getExecutionUnit().getDataSourceName().equals(statementExecuteUnit.getExecutionUnit().getDataSourceName())
                && batchExecutionUnit.getExecutionUnit().getSqlUnit().getSql().equals(statementExecuteUnit.getExecutionUnit().getSqlUnit().getSql());
    }
    
    /**
     * Get statements.
     *
     * @return statements
     */
    public List<Statement> getStatements() {
        List<Statement> result = new LinkedList<>();
        for (InputGroup<StatementExecuteUnit> each : inputGroups) {
            result.addAll(each.getInputs().stream().map(StatementExecuteUnit::getStorageResource).collect(Collectors.toList()));
        }
        return result;
    }
    
    /**
     * Get parameter sets.
     *
     * @param statement statement
     * @return parameter sets
     */
    public List<List<Object>> getParameterSet(final Statement statement) {
        return inputGroups.stream().map(each -> findStatementExecuteUnit(statement, each)).filter(Optional::isPresent).findFirst().map(Optional::get)
                .map(this::getParameterSets).orElse(Collections.emptyList());
    }
    
    private Optional<StatementExecuteUnit> findStatementExecuteUnit(final Statement statement, final InputGroup<StatementExecuteUnit> executeGroup) {
        return executeGroup.getInputs().stream().filter(each -> each.getStorageResource().equals(statement)).findFirst();
    }
    
    private List<List<Object>> getParameterSets(final StatementExecuteUnit executeUnit) {
        Optional<BatchExecutionUnit> batchExecutionUnit = batchExecutionUnits.stream().filter(each -> isSameDataSourceAndSQL(each, executeUnit)).findFirst();
        Preconditions.checkState(batchExecutionUnit.isPresent());
        return batchExecutionUnit.get().getParameterSets();
    }
    
    /**
     * Clear.
     *
     * @throws SQLException SQL exception
     */
    public void clear() throws SQLException {
        closeStatements();
        getStatements().clear();
        inputGroups.clear();
        batchCount = 0;
        batchExecutionUnits.clear();
    }
    
    private void closeStatements() throws SQLException {
        for (Statement each : getStatements()) {
            each.close();
        }
    }
}


