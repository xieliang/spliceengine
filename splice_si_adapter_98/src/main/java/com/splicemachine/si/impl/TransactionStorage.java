package com.splicemachine.si.impl;

import com.splicemachine.access.hbase.HBaseSource;
import com.splicemachine.annotations.ThreadSafe;
import com.splicemachine.constants.SIConstants;
import com.splicemachine.si.api.TimestampSource;
import com.splicemachine.si.api.txn.TxnStore;
import com.splicemachine.si.api.txn.TxnSupplier;
import com.splicemachine.si.impl.store.CompletedTxnCacheSupplier;
import com.splicemachine.si.impl.store.IgnoreTxnCacheSupplier;

/**
 * Factory class for obtaining a transaction store, Transaction supplier, etc.
 *
 * @author Scott Fines
 * Date: 7/3/14
 */
public class TransactionStorage {
		//the boxing is actually necessary, regardless of what the compiler says
		@SuppressWarnings("UnnecessaryBoxing")
		private static final Object lock = new Integer(1);
		/*
		 * The Base Txn Store for use. This store does NOT necessarily
		 * cache any transactions of any kind, and callers should be aware of that.
		 * If they want a caching Txn supplier, then consider using the
		 * cachedTransactionSupplier instead.
		 */
		private static volatile @ThreadSafe TxnStore baseStore;

		private static volatile @ThreadSafe CompletedTxnCacheSupplier cachedTransactionSupplier;

        private static volatile IgnoreTxnCacheSupplier ignoreTxnCacheSupplier;

    private static volatile TxnStoreManagement storeManagement;

    public static TxnSupplier getTxnSupplier(){
				TxnSupplier supply = cachedTransactionSupplier;
				//only do 2 volatile reads the very first few calls
				if(supply!=null) return supply;

				lazyInitialize();
				return cachedTransactionSupplier;
		}

        public static IgnoreTxnCacheSupplier getIgnoreTxnSupplier(){
            IgnoreTxnCacheSupplier supply = ignoreTxnCacheSupplier;
            //only do 2 volatile reads the very first few calls
            if(supply!=null) return supply;

            lazyInitialize();
            return ignoreTxnCacheSupplier;
        }

		public static @ThreadSafe TxnStore getTxnStore(){
				TxnStore store = baseStore;
				if(store!=null) return store;

				//initialize, then return
				lazyInitialize();
				return baseStore;
		}

		/*
		 * Useful primarily for testing
		 */
		public static void setTxnStore(@ThreadSafe TxnStore store){
				synchronized (lock){
						baseStore = store;
            cachedTransactionSupplier= new CompletedTxnCacheSupplier(baseStore,
                    SIConstants.completedTransactionCacheSize,
                    SIConstants.completedTransactionConcurrency);
            storeManagement = new TxnStoreManagement();
                    ignoreTxnCacheSupplier = new IgnoreTxnCacheSupplier();
				}
		}

		private static void lazyInitialize() {
				/*
				 * We use this to initialize our transaction stores
				 */
            synchronized (lock){
                if(baseStore==null){
                    TimestampSource tsSource = TransactionTimestamps.getTimestampSource();
                    TxnStore txnStore = null;
                    try {
                        txnStore = HBaseSource.getInstance().getTxnStore(tsSource);
                    } catch (Exception e) {
                        throw new RuntimeException("Cannot establish connection",e);
                    }

                    if(cachedTransactionSupplier==null){
                        cachedTransactionSupplier = new CompletedTxnCacheSupplier(txnStore,
                                SIConstants.completedTransactionCacheSize,
                                SIConstants.completedTransactionConcurrency);
                    }
                    txnStore.setCache(cachedTransactionSupplier);
                    baseStore = txnStore;
                }else if (cachedTransactionSupplier==null){
                    cachedTransactionSupplier = new CompletedTxnCacheSupplier(baseStore,
                            SIConstants.completedTransactionCacheSize,
                            SIConstants.completedTransactionConcurrency);
                }
                storeManagement = new TxnStoreManagement();

                if (ignoreTxnCacheSupplier == null)
                    ignoreTxnCacheSupplier = new IgnoreTxnCacheSupplier();
            }
		}

    public static TxnStoreManagement getTxnStoreManagement() {
        TxnStoreManagement store = storeManagement;
        if(store==null){
            lazyInitialize();
            store = storeManagement;
        }
        return store;
    }

    private static class TxnStoreManagement implements com.splicemachine.si.api.txn.TxnStoreManagement {
        @Override public long getTotalTxnLookups() { return baseStore.lookupCount(); }
        @Override public long getTotalTxnElevations() { return baseStore.elevationCount(); }
        @Override public long getTotalWritableTxnsCreated() { return baseStore.createdCount(); }
        @Override public long getTotalRollbacks() { return baseStore.rollbackCount(); }
        @Override public long getTotalCommits() { return baseStore.commitCount(); }
        @Override public long getTotalEvictedCacheEntries() { return cachedTransactionSupplier.getTotalEvictedEntries(); }
        @Override public long getTotalCacheHits() { return cachedTransactionSupplier.getTotalHits(); }
        @Override public long getTotalCacheMisses() { return cachedTransactionSupplier.getTotalMisses(); }
        @Override public long getTotalCacheRequests() { return cachedTransactionSupplier.getTotalRequests(); }
        @Override public float getCacheHitPercentage() { return cachedTransactionSupplier.getHitPercentage(); }
        @Override public int getCurrentCacheSize() { return cachedTransactionSupplier.getCurrentSize(); }
        @Override public int getMaxCacheSize() { return cachedTransactionSupplier.getMaxSize(); }
    }
}
