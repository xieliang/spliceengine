package com.splicemachine.storage;

import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.client.*;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Scott Fines
 *         Date: 12/22/15
 */
@NotThreadSafe
public class ClientPartition extends SkeletonHBaseClientPartition{
    private final HTableInterface table;
    private final HConnection conn;

    public ClientPartition(HTableInterface table,HConnection connection){
        this.table=table;
        this.conn = connection;
    }

    @Override
    public String getTableName(){
        return table.getName().getNameAsString();
    }

    @Override
    public void close() throws IOException{
        table.close();
    }

    @Override
    protected Result doGet(Get get) throws IOException{
        return table.get(get);
    }

    @Override
    protected ResultScanner getScanner(Scan scan) throws IOException{
        return table.getScanner(scan);
    }

    @Override
    protected void doDelete(Delete delete) throws IOException{
        table.delete(delete);
    }

    @Override
    protected void doPut(Put put) throws IOException{
        table.put(put);
    }

    @Override
    protected void doPut(List<Put> puts) throws IOException{
        table.put(puts);
    }

    @Override
    protected void doIncrement(Increment incr) throws IOException{
        table.increment(incr);
    }

    @Override
    public List<Partition> subPartitions(){
        try(HBaseAdmin admin = new HBaseAdmin(conn)){
            //TODO -sf- does this cache region information?
            List<HRegionInfo> tableRegions=admin.getTableRegions(table.getTableName());
            List<Partition> partitions = new ArrayList<>(tableRegions.size());
            for(HRegionInfo info:tableRegions){
                LazyPartitionServer owningServer=new LazyPartitionServer(conn,info,table.getTableDescriptor().getTableName());
                partitions.add(new RangedClientPartition(conn,table,info,owningServer));
            }
            return partitions;
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public PartitionServer owningServer(){
        throw new UnsupportedOperationException("A Table is not owned by a single server, but by the cluster as a whole");
    }
}
