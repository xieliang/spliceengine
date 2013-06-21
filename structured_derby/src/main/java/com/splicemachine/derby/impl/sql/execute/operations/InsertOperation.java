package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.constants.SpliceConstants;
import com.splicemachine.derby.hbase.SpliceDriver;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.impl.sql.execute.actions.InsertConstantOperation;
import com.splicemachine.derby.impl.store.access.SpliceAccessManager;
import com.splicemachine.derby.impl.store.access.hbase.HBaseRowLocation;
import com.splicemachine.derby.utils.Exceptions;
import com.splicemachine.derby.utils.marshall.KeyType;
import com.splicemachine.derby.utils.marshall.RowEncoder;
import com.splicemachine.derby.utils.marshall.RowMarshaller;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.HasIncrement;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.log4j.Logger;

import java.io.IOException;

/**
 * 
 * @author Scott Fines
 *
 * TODO:
 * 	1. Basic Inserts (insert 1 row, insert multiple small rows) - Done SF
 *  2. Insert with subselect (e.g. insert into t (name) select name from a) - Done SF
 *  3. Triggers (do with Coprocessors)
 *  4. Primary Keys (do with Coprocessors)
 *  5. Secondary Indices (do with Coprocessors)
 */
public class InsertOperation extends DMLWriteOperation implements HasIncrement {
    private static final long serialVersionUID = 1l;
	private static final Logger LOG = Logger.getLogger(InsertOperation.class);

    private ExecRow rowTemplate;
    private HTableInterface sysColumnTable;

    public InsertOperation(){
		super();
	}
	
	public InsertOperation(NoPutResultSet source,
							GeneratedMethod generationClauses, 
							GeneratedMethod checkGM) throws StandardException{
		super(source, generationClauses, checkGM, source.getActivation());
		recordConstructorTime(); 
	}

	@Override
	public void init(SpliceOperationContext context) throws StandardException{
		super.init(context);
		heapConglom = ((InsertConstantOperation)constants).getConglomerateId();

        if(constants instanceof InsertConstantOperation){
            pkCols = ((InsertConstantOperation)constants).getPkColumns();
            if(pkCols!=null)
                pkColumns = fromIntArray(pkCols);
        }
	}

    @Override
    public RowEncoder getRowEncoder() throws StandardException {
        KeyType keyType;
        int[] keyColumns = null;
        if(pkCols==null){
            //just need a salted row key
            keyType = KeyType.SALTED;
        }else{
            keyColumns = new int[pkCols.length];
            for(int i=0;i<keyColumns.length;i++){
                keyColumns[i] = pkCols[i] -1;
            }
            keyType = KeyType.BARE;
        }
        return RowEncoder.createDoubleWritingEncoder(
                getExecRowDefinition().nColumns(),
                keyColumns,null,null,keyType, RowMarshaller.denseColumnar());
    }

    @Override
	public String toString() {
		return "Insert{destTable="+heapConglom+",source=" + source + "}";
	}

    @Override
    public String prettyPrint(int indentLevel) {
        return "Insert"+super.prettyPrint(indentLevel);
    }

    @Override
    public DataValueDescriptor increment(int columnPosition, long increment) throws StandardException {
        int index = columnPosition-1;

        HBaseRowLocation rl = (HBaseRowLocation)((InsertConstantOperation) constants).getAutoincRowLocation()[index];

        byte[] rlBytes = rl.getBytes();

        if(sysColumnTable==null){
            sysColumnTable = SpliceAccessManager.getHTable(SpliceConstants.SEQUENCE_TABLE_NAME_BYTES);
        }

        Sequence sequence;
        try {
            sequence = SpliceDriver.driver().getSequencePool().get(new Sequence.Key(sysColumnTable,rlBytes,
                    getTransactionID(),heapConglom,columnPosition));
        } catch (Exception e) {
            throw Exceptions.parseException(e);
        }

        long nextValue = sequence.getNext();

        if(rowTemplate==null)
            rowTemplate = getExecRowDefinition();
        DataValueDescriptor dvd = rowTemplate.cloneColumn(columnPosition);
        dvd.setValue(nextValue);
        return dvd;
    }

    @Override
    public void close() throws StandardException {
        if(sysColumnTable!=null){
            try{
                sysColumnTable.close();
            } catch (IOException e) {
                SpliceLogUtils.error(LOG,"Unable to close htable, beware of potential memory problems!",e);
            }
        }
    }
}
