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

package com.splicemachine.timestamp.impl;

import com.splicemachine.timestamp.api.TimestampBlockManager;
import com.splicemachine.timestamp.api.TimestampIOException;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;

public class TimestampServerHandler extends TimestampBaseHandler {

    private static final Logger LOG = Logger.getLogger(TimestampServerHandler.class);

    private volatile TimestampOracle oracle;
    private TimestampBlockManager timestampBlockManager;
    private int blockSize;

    public TimestampServerHandler(TimestampBlockManager timestampBlockManager, int blockSize) {
        super();
        this.timestampBlockManager=timestampBlockManager;
        this.blockSize = blockSize;
    }

    public void initializeIfNeeded() throws TimestampIOException{
        SpliceLogUtils.trace(LOG, "Checking whether initialization is needed");
        if(oracle==null){
            synchronized(this){
                if(oracle==null){
                    oracle=TimestampOracle.getInstance(timestampBlockManager,blockSize);
                }
            }
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        assert oracle != null;

        ChannelBuffer buf = (ChannelBuffer) e.getMessage();
        assert buf != null;
        ensureReadableBytes(buf, TimestampServer.FIXED_MSG_RECEIVED_LENGTH);

        final short callerId = buf.readShort();
        ensureReadableBytes(buf, 0);

        SpliceLogUtils.trace(LOG, "Received timestamp request from client. Caller id = %s", callerId);
        long nextTimestamp = oracle.getNextTimestamp();
        assert nextTimestamp > 0;


        //
        // Respond to the client
        //

        ChannelBuffer writeBuf = ChannelBuffers.buffer(TimestampServer.FIXED_MSG_SENT_LENGTH);
        writeBuf.writeShort(callerId);
        writeBuf.writeLong(nextTimestamp);
        SpliceLogUtils.debug(LOG, "Responding to caller %s with timestamp %s", callerId, nextTimestamp);
        ChannelFuture futureResponse = e.getChannel().write(writeBuf); // Could also use Channels.write
        futureResponse.addListener(new ChannelFutureListener() {
                                       @Override
                                       public void operationComplete(ChannelFuture cf) throws Exception {
                                           if (!cf.isSuccess()) {
                                               throw new TimestampIOException(
                                                       "Failed to respond successfully to caller id " + callerId, cf.getCause());
                                           }
                                       }
                                   }
        );

        super.messageReceived(ctx, e);
    }

    protected void doError(String message, Throwable t, Object... args) {
        SpliceLogUtils.error(LOG, message, t, args);
    }

}
