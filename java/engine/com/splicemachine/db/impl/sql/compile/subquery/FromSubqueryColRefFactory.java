package com.splicemachine.db.impl.sql.compile.subquery;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
import com.splicemachine.db.iapi.sql.compile.NodeFactory;
import com.splicemachine.db.impl.ast.PredicateUtils;
import com.splicemachine.db.impl.sql.compile.BinaryRelationalOperatorNode;
import com.splicemachine.db.impl.sql.compile.ColumnReference;
import com.splicemachine.db.impl.sql.compile.FromSubquery;
import com.splicemachine.db.impl.sql.compile.ResultColumn;

/**
 * Shared code for creating a ColumnReference to a ResultColumn in a FromSubquery.
 */
public class FromSubqueryColRefFactory {

    /**
     * We have a recently created FromSubquery node and we want to create a new ColumnReference to it.
     *
     * EXAMPLE:
     * <pre>
     *     select A.*
     *       from A
     *       join (select 1,b1,sum(b2) from B where b2 > x) foo
     *       where a1 > 1;
     * </pre>
     *
     * Here we are creating a column reference that can be used in the outer query to reference foo.b1 so that we can
     * add predicates to the outer query such as a1 = foo.b1
     *
     * @param outerSelectNestingLevel select node of the outer query.
     * @param newFromSubquery         the FromSubquery we are creating as part of subquery flattening.
     * @param fromSubqueryColumnToRef 0-based index of the FromSubquery column we want to reference.
     */
    public static ColumnReference build(int outerSelectNestingLevel,
                                        FromSubquery newFromSubquery,
                                        int fromSubqueryColumnToRef,
                                        NodeFactory nodeFactory,
                                        ContextManager contextManager) throws StandardException {

        ResultColumn resultColumn = newFromSubquery.getResultColumns().elementAt(fromSubqueryColumnToRef);

        ColumnReference colRef = (ColumnReference) nodeFactory.getNode(C_NodeTypes.COLUMN_REFERENCE,
                resultColumn.getName(),
                newFromSubquery.getTableName(),
                contextManager);

        colRef.setSource(resultColumn);
        colRef.setTableNumber(newFromSubquery.getTableNumber());
        colRef.setTableNameNode(newFromSubquery.getTableName());
        colRef.setColumnNumber(resultColumn.getVirtualColumnId());
        colRef.setNestingLevel(outerSelectNestingLevel);
        colRef.setSourceLevel(outerSelectNestingLevel);

        return colRef;
    }

    /**
     * Modify one side of the given predicate to contain the new FromSubquery ColumnReference.
     *
     * The passed BRON is a predicate like 'a1 = b1' which we are moving from a subquery to the outer query.  This
     * method finds the subquery level CR (b1 below) and replaces it with a column reference to the new FromSubquery.
     *
     * <pre>
     * select * form A where exists(select 1 from B where a1 = b1);
     * </pre>
     *
     * @param pred                In this BRON
     * @param colRef              Substitute this colRef
     * @param subquerySourceLevel for the existing colRef at this source level.
     */
    public static void replace(BinaryRelationalOperatorNode pred, ColumnReference colRef, int subquerySourceLevel) {
        ColumnReference leftOperand = (ColumnReference) pred.getLeftOperand();
        ColumnReference rightOperand = (ColumnReference) pred.getRightOperand();
        // b1 = a1 TO foo.b1 = a1
        if (PredicateUtils.isLeftColRef(pred, subquerySourceLevel)) {
            pred.setLeftOperand(colRef);
            rightOperand.setNestingLevel(rightOperand.getSourceLevel());
        }
        // a1 = b1 TO a1 = foo.b1
        else if (PredicateUtils.isRightColRef(pred, subquerySourceLevel)) {
            pred.setRightOperand(colRef);
            leftOperand.setNestingLevel(leftOperand.getSourceLevel());
        }
    }
}
