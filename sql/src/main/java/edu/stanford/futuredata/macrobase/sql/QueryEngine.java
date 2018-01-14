package edu.stanford.futuredata.macrobase.sql;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import edu.stanford.futuredata.macrobase.analysis.classify.Classifier;
import edu.stanford.futuredata.macrobase.analysis.summary.apriori.APrioriSummarizer;
import edu.stanford.futuredata.macrobase.analysis.summary.ratios.ExplanationMetric;
import edu.stanford.futuredata.macrobase.datamodel.DataFrame;
import edu.stanford.futuredata.macrobase.datamodel.Schema.ColType;
import edu.stanford.futuredata.macrobase.ingest.CSVDataFrameLoader;
import edu.stanford.futuredata.macrobase.sql.tree.ComparisonExpression;
import edu.stanford.futuredata.macrobase.sql.tree.ComparisonExpressionType;
import edu.stanford.futuredata.macrobase.sql.tree.DiffQuerySpecification;
import edu.stanford.futuredata.macrobase.sql.tree.DoubleLiteral;
import edu.stanford.futuredata.macrobase.sql.tree.Expression;
import edu.stanford.futuredata.macrobase.sql.tree.Identifier;
import edu.stanford.futuredata.macrobase.sql.tree.ImportCsv;
import edu.stanford.futuredata.macrobase.sql.tree.Literal;
import edu.stanford.futuredata.macrobase.sql.tree.LogicalBinaryExpression;
import edu.stanford.futuredata.macrobase.sql.tree.LogicalBinaryExpression.Type;
import edu.stanford.futuredata.macrobase.sql.tree.NotExpression;
import edu.stanford.futuredata.macrobase.sql.tree.NullLiteral;
import edu.stanford.futuredata.macrobase.sql.tree.OrderBy;
import edu.stanford.futuredata.macrobase.sql.tree.QueryBody;
import edu.stanford.futuredata.macrobase.sql.tree.QuerySpecification;
import edu.stanford.futuredata.macrobase.sql.tree.Relation;
import edu.stanford.futuredata.macrobase.sql.tree.Select;
import edu.stanford.futuredata.macrobase.sql.tree.SelectItem;
import edu.stanford.futuredata.macrobase.sql.tree.SortItem;
import edu.stanford.futuredata.macrobase.sql.tree.SortItem.Ordering;
import edu.stanford.futuredata.macrobase.sql.tree.SplitQuery;
import edu.stanford.futuredata.macrobase.sql.tree.StringLiteral;
import edu.stanford.futuredata.macrobase.sql.tree.Table;
import edu.stanford.futuredata.macrobase.sql.tree.TableSubquery;
import edu.stanford.futuredata.macrobase.util.MacrobaseException;
import edu.stanford.futuredata.macrobase.util.MacrobaseSQLException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(MacroBaseSQLRepl.class);

    private final Map<String, DataFrame> tablesInMemory;

    QueryEngine() {
        tablesInMemory = new HashMap<>();
    }

    DataFrame importTableFromCsv(ImportCsv importStatement) throws MacrobaseSQLException {
        final String filename = importStatement.getFilename();
        final String tableName = importStatement.getTableName().toString();
        final Map<String, ColType> schema = importStatement.getSchema();
        try {
            DataFrame df = new CSVDataFrameLoader(filename).setColumnTypes(schema).load();
            tablesInMemory.put(tableName, df);
            return df;
        } catch (Exception e) {
            throw new MacrobaseSQLException(e.getMessage());
        }
    }

    DataFrame executeQuery(QueryBody query) throws MacrobaseException {
        if (query instanceof QuerySpecification) {
            QuerySpecification querySpec = (QuerySpecification) query;
            log.debug(querySpec.toString());
            return executeQuerySpec(querySpec);

        } else if (query instanceof DiffQuerySpecification) {
            DiffQuerySpecification diffQuery = (DiffQuerySpecification) query;
            log.debug(diffQuery.toString());
            return executeDiffQuerySpec(diffQuery);
        }
        throw new MacrobaseSQLException(
            "query of type " + query.getClass().getSimpleName() + " not yet supported");
    }

    private DataFrame executeDiffQuerySpec(final DiffQuerySpecification diffQuery)
        throws MacrobaseException {
        // TODO: if an attributeCol isn't in a select col, don't include it
        final List<String> explainCols = diffQuery.getAttributeCols().stream()
            .map(Identifier::toString)
            .collect(toImmutableList());

        final String outlierColName = "outlier_col";
        DataFrame dfToExplain = null;

        if (diffQuery.hasTwoArgs()) {
            final TableSubquery first = diffQuery.getFirst().get();
            final TableSubquery second = diffQuery.getSecond().get();

            // execute subqueries
            final DataFrame outliersDf = executeQuery(first.getQuery().getQueryBody());
            final DataFrame inliersDf = executeQuery(second.getQuery().getQueryBody());

            // TODO: should be able to check this without having to execute the two subqueries
            if (!outliersDf.getSchema().hasColumns(explainCols) || !inliersDf.getSchema()
                .hasColumns(explainCols)) {
                throw new MacrobaseSQLException(
                    "ON " + Joiner.on(", ").join(explainCols) + " not present in either"
                        + " outlier or inlier subquery");
            }
            dfToExplain = combineOutliersAndInliers(outlierColName, outliersDf, inliersDf);

        } else {
            // splitQuery
            final SplitQuery splitQuery = diffQuery.getSplitQuery().get();
            DataFrame inputDf;
            final Relation inputRelation = splitQuery.getInputRelation();
            if (inputRelation instanceof TableSubquery) {
                inputDf = executeQuery(((TableSubquery) inputRelation).getQuery().getQueryBody());
            } else {
                // instance of Table
                inputDf = getTable(((Table) inputRelation).getName().toString());
            }

            if (!inputDf.getSchema().hasColumns(explainCols)) {
                throw new MacrobaseSQLException(
                    "ON " + Joiner.on(", ").join(explainCols) + " not present in table "
                        + inputRelation);
            }

            final String classifierType = splitQuery.getClassifierName().getValue();
            try {
                final Classifier classifier = Classifier
                    .getClassifier(classifierType, splitQuery.getClassifierArgs().stream().map(
                        Expression::toString).collect(toList()));
                classifier.setOutputColumnName(outlierColName);
                classifier.process(inputDf);
                dfToExplain = classifier.getResults();

            } catch (MacrobaseException e) {
                // this comes from instantiating the classifier; re-throw
                throw e;
            } catch (Exception e) {
                e.printStackTrace();
                // TODO: get rid of Exception thrown by classifier.process
            }
        }

        final double minRatioMetric = diffQuery.getMinRatioExpression().getMinRatio();
        final double minSupport = diffQuery.getMinSupportExpression().getMinSupport();
        final ExplanationMetric ratioMetric = ExplanationMetric
            .getMetricFn(diffQuery.getRatioMetricExpr().getFuncName().toString());
        final int order = diffQuery.getMaxCombo().getValue();

        // execute diff
        // TODO: add support for "ON *"
        DataFrame df = diff(dfToExplain, outlierColName, explainCols, minRatioMetric, minSupport,
            ratioMetric, order);

        return evaluateSQLClauses(diffQuery, df);
    }

    private DataFrame combineOutliersAndInliers(final String outlierColName,
        final DataFrame outliersDf, final DataFrame inliersDf) throws MacrobaseSQLException {

        // Add column "outlier_col" to both outliers (all 1.0) and inliers (all 0.0)
        outliersDf.addColumn(outlierColName,
            DoubleStream.generate(() -> 1.0).limit(outliersDf.getNumRows()).toArray());
        inliersDf.addColumn(outlierColName,
            DoubleStream.generate(() -> 0.0).limit(outliersDf.getNumRows()).toArray());
        return DataFrame.unionAll(Lists.newArrayList(outliersDf, inliersDf));
    }

    private DataFrame diff(final DataFrame combined, final String outlierColName,
        final List<String> cols, final double minRatioMetric, final double minSupport,
        final ExplanationMetric ratioMetric, final int order) throws MacrobaseException {

        final APrioriSummarizer summarizer = new APrioriSummarizer();
        summarizer.setRatioMetric(ratioMetric)
            .setMaxOrder(order)
            .setMinRatioMetric(minRatioMetric)
            .setMinSupport(minSupport)
            .setOutlierColumn(outlierColName)
            .setAttributes(cols);

        summarizer.process(combined);
        return summarizer.getResults().toDataFrame(cols);
    }

    /**
     * Evaluate standard SQL clauses: SELECT, WHERE, ORDER BY, and LIMIT. TODO: support GROUP BY and
     * HAVING clauses
     *
     * @param query the query that contains the clauses
     * @param df the DataFrame to apply these clauses to
     * @return a new DataFrame, the result of applying all of these clauses
     */
    private DataFrame evaluateSQLClauses(final QueryBody query, final DataFrame df)
        throws MacrobaseSQLException {
        // TODO: we need to figure out a smarter ordering of these. For example,
        // if we have an ORDER BY, we don't need to sort columns that are never going to be in the
        // final output (i.e. the ones not in the SELECT). Basically, we need to do two passes of
        // SELECTs: one with all original projections + the columns in the WHERE clauses and ORDER BY
        // clauses, and then a second with just the original projections. That should be correct
        // and give us better performance.
        DataFrame resultDf = evaluateWhereClause(df, query.getWhere());
        resultDf = evaluateOrderByClause(resultDf, query.getOrderBy());
        resultDf = evaluateLimitClause(resultDf, query.getLimit());
        return evaluateSelectClause(resultDf, query.getSelect());
    }

    /**
     * Evaluate ORDER BY clause. For now, we only support sorting by a single column.
     */
    private DataFrame evaluateOrderByClause(DataFrame df, Optional<OrderBy> orderByOpt) {
        if (!orderByOpt.isPresent()) {
            return df;
        }
        final OrderBy orderBy = orderByOpt.get();
        // For now, we only support sorting by a single column
        // TODO: support multi-column sort
        final SortItem sortItem = orderBy.getSortItems().get(0);
        final String sortCol = ((Identifier) sortItem.getSortKey()).getValue();
        return df.orderBy(sortCol, sortItem.getOrdering() == Ordering.ASCENDING);
    }

    /**
     * Execute a standard SQL query. For now, we ignore GROUP BY, HAVING, and JOIN clauses
     *
     * @param query The QuerySpecification object for the SQL query
     * @return A DataFrame containing the results of the SQL query
     */
    private DataFrame executeQuerySpec(final QuerySpecification query)
        throws MacrobaseSQLException {
        Table table = (Table) query.getFrom().get();
        final String tableName = table.getName().toString();
        final DataFrame df = getTable(tableName);
        return evaluateSQLClauses(query, df);
    }

    /**
     * Get table as DataFrame that has previously been loaded into memory
     *
     * @param tableName String that uniquely identifies table
     * @return DataFrame for table
     * @throws MacrobaseSQLException if the table has not been loaded into memory and does not
     * exist
     */
    private DataFrame getTable(String tableName) throws MacrobaseSQLException {
        if (!tablesInMemory.containsKey(tableName)) {
            throw new MacrobaseSQLException("Table " + tableName + " does not exist");
        }
        return tablesInMemory.get(tableName);
    }

    /**
     * Evaluate Select clause of SQL query. If the clause is 'SELECT *' the same DataFrame is
     * returned unchanged. TODO: add support for DISTINCT queries
     *
     * @param df The DataFrame to apply the Select clause on
     * @param select The Select clause to evaluate
     * @return A new DataFrame with the result of the Select clause applied
     */
    private DataFrame evaluateSelectClause(DataFrame df, Select select) {
        final List<String> projections = select.getSelectItems().stream()
            .map(SelectItem::toString)
            .collect(toImmutableList());
        if (projections.size() == 1 && projections.get(0).equalsIgnoreCase("*")) {
            return df; // SELECT * -> relation is unchanged
        }
        return df.project(projections);
    }

    /**
     * Evaluate LIMIT clause of SQL query, return the top n rows of the DataFrame, where `n' is
     * specified in "LIMIT n"
     *
     * @param df The DataFrame to apply the LIMIT clause on
     * @param limitStr The number of rows (either an integer or "ALL") as a String in the LIMIT
     * clause
     * @return A new DataFrame with the result of the LIMIT clause applied
     */
    private DataFrame evaluateLimitClause(final DataFrame df, final Optional<String> limitStr) {
        if (limitStr.isPresent()) {
            try {
                return df.limit(Integer.parseInt(limitStr.get()));
            } catch (NumberFormatException e) {
                // LIMIT ALL, catch NumberFormatException and do nothing
                return df;
            }
        }
        return df;
    }

    /**
     * Evaluate Where clause of SQL query
     *
     * @param df the DataFrame to filter
     * @param whereClauseOpt An Optional Where clause (of type Expression) to evaluate for each row
     * in <tt>df</tt>
     * @return A new DataFrame that contains the rows for which @whereClause evaluates to true. If
     * <tt>whereClauseOpt</tt> is not Present, we return <tt>df</tt>
     */
    private DataFrame evaluateWhereClause(final DataFrame df,
        final Optional<Expression> whereClauseOpt) throws MacrobaseSQLException {
        if (!whereClauseOpt.isPresent()) {
            return df;
        }
        final Expression whereClause = whereClauseOpt.get();
        final BitSet mask = getMask(df, whereClause);
        return df.filter(mask);
    }

    // ********************* Helper methods for evaluating Where clauses **********************

    // Recursive method that generates a boolean mask (a BitSet) for a Where clause
    private BitSet getMask(DataFrame df, Expression whereClause) throws MacrobaseSQLException {
        if (whereClause instanceof NotExpression) {
            final NotExpression notExpr = (NotExpression) whereClause;
            final BitSet mask = getMask(df, notExpr.getValue());
            mask.flip(0, df.getNumRows());
            return mask;

        } else if (whereClause instanceof LogicalBinaryExpression) {
            final LogicalBinaryExpression binaryExpr = (LogicalBinaryExpression) whereClause;
            final BitSet leftMask = getMask(df, binaryExpr.getLeft());
            final BitSet rightMask = getMask(df, binaryExpr.getRight());
            if (binaryExpr.getType() == Type.AND) {
                leftMask.and(rightMask);
                return leftMask;
            } else {
                // Type.OR
                leftMask.or(rightMask);
                return leftMask;
            }

        } else if (whereClause instanceof ComparisonExpression) {
            // base case
            final ComparisonExpression compareExpr = (ComparisonExpression) whereClause;
            final Expression left = compareExpr.getLeft();
            final Expression right = compareExpr.getRight();
            final ComparisonExpressionType type = compareExpr.getType();

            if (left instanceof Literal && right instanceof Literal) {
                final boolean val = left.equals(right);
                final BitSet mask = new BitSet(df.getNumRows());
                mask.set(0, df.getNumRows(), val);
                return mask;
            } else if (left instanceof Literal && right instanceof Identifier) {
                return maskForPredicate(df, (Literal) left, (Identifier) right, type);
            } else if (right instanceof Literal && left instanceof Identifier) {
                return maskForPredicate(df, (Literal) right, (Identifier) left, type);
            }
        }
        // We only support comparison expressions, logical AND/OR combinations of comparison
        // expressions, and NOT comparison expressions
        throw new MacrobaseSQLException("Boolean expression not supported");
    }

    private BitSet maskForPredicate(final DataFrame df, final Literal literal,
        final Identifier identifier,
        final ComparisonExpressionType compExprType) throws MacrobaseSQLException {
        final String colName = identifier.getValue();
        final int colIndex = df.getSchema().getColumnIndex(colName);
        final ColType colType = df.getSchema().getColumnType(colIndex);

        if (colType == ColType.DOUBLE) {
            if (!(literal instanceof DoubleLiteral)) {
                throw new MacrobaseSQLException(
                    "Column " + colName + " has type " + colType + ", but " + literal
                        + " is not a DoubleLiteral");
            }

            return df.getMaskForFilter(colIndex,
                generateLambdaForPredicate(((DoubleLiteral) literal).getValue(), compExprType));
        } else {
            // colType == ColType.STRING
            if (literal instanceof StringLiteral) {
                return df.getMaskForFilter(colIndex,
                    generateLambdaForPredicate(((StringLiteral) literal).getValue(), compExprType));
            } else if (literal instanceof NullLiteral) {
                return df.getMaskForFilter(colIndex,
                    generateLambdaForPredicate(null, compExprType));
            } else {
                throw new MacrobaseSQLException(
                    "Column " + colName + " has type " + colType + ", but " + literal
                        + " is not StringLiteral");
            }
        }
    }

    private DoublePredicate generateLambdaForPredicate(double y,
        ComparisonExpressionType compareExprType) throws MacrobaseSQLException {
        switch (compareExprType) {
            case EQUAL:
                return (x) -> x == y;
            case NOT_EQUAL:
            case IS_DISTINCT_FROM:
                // IS DISTINCT FROM is true when x and y have different values or
                // if one of them is NULL and the other isn't.
                // x and y can never be NULL here, so it's the same as NOT_EQUAL
                return (x) -> x != y;
            case LESS_THAN:
                return (x) -> x < y;
            case LESS_THAN_OR_EQUAL:
                return (x) -> x <= y;
            case GREATER_THAN:
                return (x) -> x > y;
            case GREATER_THAN_OR_EQUAL:
                return (x) -> x >= y;
            default:
                throw new MacrobaseSQLException(compareExprType + " is not supported");
        }
    }

    private Predicate<Object> generateLambdaForPredicate(final String y,
        final ComparisonExpressionType compareExprType) throws MacrobaseSQLException {
        switch (compareExprType) {
            case EQUAL:
                return (x) -> Objects.equals(x, y);
            case NOT_EQUAL:
            case IS_DISTINCT_FROM:
                // IS DISTINCT FROM is true when x and y have different values or
                // if one of them is NULL and the other isn't
                return (x) -> !Objects.equals(x, y);
            default:
                throw new MacrobaseSQLException(compareExprType + " is not supported");
        }
    }
}
