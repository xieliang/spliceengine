package com.splicemachine.hbase.async;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.splicemachine.constants.SpliceConstants;
import com.splicemachine.constants.bytes.BytesUtil;
import com.splicemachine.hbase.RowKeyDistributor;
import com.splicemachine.hbase.RowKeyDistributorByHashPrefix;
import com.splicemachine.stats.*;
import com.splicemachine.stats.Timer;
import com.splicemachine.utils.NullStopIterator;
import com.splicemachine.utils.SpliceLogUtils;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.hbase.async.HBaseClient;
import org.hbase.async.KeyValue;
import org.hbase.async.Scanner;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * An asynchronous, vectored scanner that returns data in sorted order.
 *
 * @author Scott Fines
 * Date: 7/30/14
 */
public class SortedGatheringScanner implements AsyncScanner{
    private static final Logger LOG = Logger.getLogger(SortedGatheringScanner.class);
    private final Timer timer;
    private final Counter remoteBytesCounter;
    private final Comparator<byte[]> sortComparator;

    private final SubScanner[] scanners;
    private List<KeyValue>[] nextAnswers;
    private boolean[] exhaustedScanners;

    public static AsyncScanner newScanner(Scan baseScan,int maxQueueSize,
                                          MetricFactory metricFactory,
                                          Function<Scan,Scanner> conversionFunction,
                                          RowKeyDistributor rowKeyDistributor,
                                          Comparator<byte[]> sortComparator) throws IOException {

        Scan[] distributedScans = rowKeyDistributor.getDistributedScans(baseScan);
        if(distributedScans.length<=1){
            return new SimpleAsyncScanner(conversionFunction.apply(baseScan),metricFactory);
        }

        List<Scanner> scans = Lists.newArrayListWithExpectedSize(distributedScans.length);
        for(Scan scan:distributedScans){
            SpliceLogUtils.info(LOG,"Scanning area [%s,%s)",Bytes.toStringBinary(scan.getStartRow()),Bytes.toStringBinary(scan.getStopRow()));
            scans.add(conversionFunction.apply(scan));
        }

        return new SortedGatheringScanner(scans,maxQueueSize,sortComparator,metricFactory);
    }

    public SortedGatheringScanner(List<Scanner> scanners, int maxQueueSize, Comparator<byte[]> sortComparator,MetricFactory metricFactory){
        this.timer = metricFactory.newTimer();
        this.remoteBytesCounter = metricFactory.newCounter();
        if(sortComparator==null)
            this.sortComparator = Bytes.BYTES_COMPARATOR;
        else
            this.sortComparator = sortComparator;

        this.scanners = new SubScanner[scanners.size()];
        for(int i=0;i<scanners.size();i++){
            Scanner scanner = scanners.get(i);
            //each scanner gets 1/n the size of the queue
            this.scanners[i] = new SubScanner(maxQueueSize/scanners.size(), scanner);
        }
        //noinspection unchecked
        this.nextAnswers = new List[scanners.size()];
        this.exhaustedScanners = new boolean[scanners.size()];
    }

    @Override
    public void open() throws IOException {
        for(SubScanner scanner:scanners){
            scanner.open();
        }
    }

    @Override public TimeView getRemoteReadTime() { return timer.getTime(); }
    @Override public long getRemoteBytesRead() { return remoteBytesCounter.getTotal(); }
    @Override public long getRemoteRowsRead() { return timer.getNumEvents(); }
    @Override public TimeView getLocalReadTime() { return Metrics.noOpTimeView(); }
    @Override public long getLocalBytesRead() { return 0; }
    @Override public long getLocalRowsRead() { return 0; }

    @Override
    public Result next() throws IOException {
        try {
            List<KeyValue> kvs = nextKeyValues();
            if(kvs==null||kvs.size()<=0) return null;

            return new Result(AsyncScannerUtils.convertFromAsync(kvs));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public Result[] next(int nbRows) throws IOException {
        List<Result> results = Lists.newArrayListWithExpectedSize(nbRows);
        Result r;
        while((r = next())!=null){
            results.add(r);
        }
        return results.toArray(new Result[results.size()]);
    }

    @Override
    public void close() {
        for(SubScanner scanner:scanners){
            scanner.close();
        }
    }

    @Override
    public List<KeyValue> nextKeyValues() throws Exception {
        timer.startTiming();
        List<KeyValue> currMinList = null;
        KeyValue currMinFirst = null;
        int currMinPos = -1;
        for(int i=0;i<nextAnswers.length;i++){
            if(exhaustedScanners[i]) continue;

            List<KeyValue> next;
            if(nextAnswers[i]!=null)
                next = nextAnswers[i];
            else{
                /*
                 * We used this value last time, make sure it's filled
                 */
                next = scanners[i].next();
                if(next==null || next.size()<=0){
                    exhaustedScanners[i] = true;
                    continue;
                }
                nextAnswers[i] = next;
            }

            if(currMinFirst==null){
                currMinFirst = next.get(0);
                currMinList = next;
                currMinPos = i;
            }else{
                KeyValue first = next.get(0);
                if(sortComparator.compare(first.key(),currMinFirst.key())<0){
                    currMinList = next;
                    currMinFirst = first;
                    currMinPos = i;
                }
            }
        }
        if(currMinFirst==null)
            timer.stopTiming();
        else{
            timer.tick(1);
            nextAnswers[currMinPos] = null;
        }
        return currMinList;
    }


    @Override
    public Iterator<Result> iterator() {
        return new NullStopIterator<Result>() {
            @Override protected Result nextItem() throws IOException { return SortedGatheringScanner.this.next(); }
            @Override public void close() throws IOException { SortedGatheringScanner.this.close(); }
        };
    }

    private static final List<KeyValue> POISON_PILL = Collections.emptyList();
    private static class SubScanner implements Callback<Void,ArrayList<ArrayList<KeyValue>>>{

        private final BlockingQueue<List<KeyValue>> resultQueue;
        private final int maxQueueSize;
        private final Scanner scanner;

        private List<KeyValue> peeked;

        private volatile boolean done;
        private volatile Deferred<Void> request = null;

        private SubScanner(int maxQueueSize, Scanner scanner) {
            this.maxQueueSize = maxQueueSize;
            this.scanner = scanner;
            this.resultQueue = new LinkedBlockingQueue<List<KeyValue>>();
        }

        private List<KeyValue> peekNext() throws IOException{
            if(peeked!=null) return peeked;

            try {
                List<KeyValue> take = resultQueue.take();
                if(take==POISON_PILL) //the scanner finished, but there's nothing left
                    return null;
                peeked = take;
                if(!done && request==null){
                    request = scanner.nextRows().addCallback(this); //initiate a new request
                }
                return take;
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        private List<KeyValue> next() throws IOException{
            List<KeyValue> n = peekNext();
            peeked = null;
            return n;
        }

        @Override
        public Void call(ArrayList<ArrayList<KeyValue>> arg) throws Exception {
            SpliceLogUtils.info(LOG, "Received callback with %d rows", arg == null ? 0 : arg.size());
            if(arg==null || done){
                SpliceLogUtils.info(LOG,"Completed scan");
                resultQueue.offer(POISON_PILL);
                done = true;
                return null;
            }
            resultQueue.addAll(arg);
            if(resultQueue.size()>=maxQueueSize){
                SpliceLogUtils.info(LOG,"Exceeded queue size, pausing processing");
                request = null;
                return null;
            }

            if(scanner.onFinalRegion() && arg.size()<scanner.getMaxNumRows()){
                SpliceLogUtils.info(LOG,"Completed scanning rows, terminating early");
                resultQueue.offer(POISON_PILL); //make sure that poison_pill is on the queue
                done = true;
                scanner.close();
                request = null;
                return null;
            }

            return null;
        }

        public void open() {
            request = scanner.nextRows().addCallback(this);
        }

        public void close() {
            done = true;
            scanner.close();
        }
    }

    public static void main(String...args) throws Exception{
        Logger.getRootLogger().addAppender(new ConsoleAppender(new SimpleLayout()));
        Logger.getRootLogger().setLevel(Level.INFO);
        Scan baseScan = new Scan();
        byte[] startRow = Bytes.toBytesBinary("5\\x14x\\xDB\\xE7I@\\x01");
        baseScan.setStartRow(startRow);
        baseScan.setStopRow(BytesUtil.unsignedCopyAndIncrement(startRow));

        RowKeyDistributorByHashPrefix.Hasher hasher = new RowKeyDistributorByHashPrefix.Hasher(){

            @Override
            public byte[] getHashPrefix(byte[] originalKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[][] getAllPossiblePrefixes() {
                byte[][] buckets = new byte[16][];
                for(int i=0;i<buckets.length;i++){
                    buckets[i] = new byte[]{(byte)(i*0xF0)};
                }
                return buckets;
            }

            @Override
            public int getPrefixLength(byte[] adjustedKey) {
                return 1;
            }
        };
        RowKeyDistributor keyDistributor = new RowKeyDistributorByHashPrefix(hasher);

        final HBaseClient client = SimpleAsyncScanner.HBASE_CLIENT;
        try{
            AsyncScanner scanner = SortedGatheringScanner.newScanner(baseScan,1024,
                    Metrics.noOpMetricFactory(), new Function<Scan, Scanner>() {
                @Nullable
                @Override
                public Scanner apply(@Nullable Scan scan) {
                    Scanner scanner = client.newScanner(SpliceConstants.TEMP_TABLE_BYTES);
                    scanner.setStartKey(scan.getStartRow());
                    byte[] stop = scan.getStopRow();
                    if(stop.length>0)
                        scanner.setStopKey(stop);
                    return scanner;
                }
            },keyDistributor,null);
            scanner.open();

            Result r;
            while((r = scanner.next())!=null){
                System.out.println(Bytes.toStringBinary(r.getRow()));
            }
        }finally{
            client.shutdown().join();
        }
    }
}
