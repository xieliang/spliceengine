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

package com.splicemachine.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

import com.google.common.base.Supplier;
import com.splicemachine.encoding.MultiFieldDecoder;
import com.splicemachine.storage.index.BitIndex;
import com.splicemachine.storage.index.BitIndexing;
import com.splicemachine.utils.ByteSlice;

/**
 * Holds the bytes for a row, a BitIndex for that row, and a MultiFieldDecoder for that row and provides methods
 * for using them together to decode the row.
 *
 * @author Scott Fines
 *         Created on: 7/5/13
 */
public class EntryDecoder implements Supplier<MultiFieldDecoder> {

    /* The bytes we are currently decoding. */
    private ByteSlice currentData;
    /* Just the BitIndex bytes from the last set of bytes we were decoding */
    private ByteSlice lastIndexData;

    /* Position within currentData of the first delimiter (end of BitIndex)  */
    private int dataOffset;

    private BitIndex bitIndex;
    private MultiFieldDecoder decoder;

    public EntryDecoder() {
    }

    public EntryDecoder(byte[] bytes) {
        set(bytes);
    }

    public void set(byte[] bytes) {
        set(bytes, 0, bytes.length);
    }

    public void set(byte[] bytes, int offset, int length) {
        if (currentData == null) {
            currentData = new ByteSlice();
            lastIndexData = new ByteSlice();
        }

        currentData.set(bytes, offset, length);

        rebuildBitIndex();
        if (decoder != null)
            decoder.set(bytes, offset + dataOffset, length - dataOffset);
    }

    private void rebuildBitIndex() {
        //find separator byte
        dataOffset = currentData.find((byte) 0x00, 0);

        if (lastIndexData.equals(currentData, dataOffset)) {
            dataOffset++;
            return;
        }

        int offset = currentData.offset();
        byte[] data = currentData.array();

        //build a new bitIndex from the data
        byte headerByte = data[offset];
        if ((headerByte & 0x80) != 0) {
            if ((headerByte & 0x40) != 0) {
                bitIndex = BitIndexing.compressedBitMap(data, offset, dataOffset);
            } else {
                bitIndex = BitIndexing.uncompressedBitMap(data, offset, dataOffset);
            }
        } else {
            //sparse index
            bitIndex = BitIndexing.sparseBitMap(data, offset, dataOffset);
        }
        lastIndexData.set(data, offset, dataOffset);
        dataOffset++;
    }

    public boolean isSet(int position) {
        return bitIndex.isSet(position);
    }

    public boolean nextIsNull(int position) {
        if (bitIndex.isFloatType(position)) {
            if (decoder.nextIsNullFloat()) {
                return true;
            }
        } else if (bitIndex.isDoubleType(position)) {
            if (decoder.nextIsNullDouble()) {
                return true;
            }
        }
        return decoder.nextIsNull();
    }

    public BitIndex getCurrentIndex() {
        return bitIndex;
    }

    public byte[] getData(int position) throws IOException {
        if (!isSet(position)) throw new NoSuchElementException();

        //get number of fields to skip
        int fieldsToSkip = bitIndex.cardinality(position);
        int fieldSkipped = 0;
        int start;
        int length = currentData.length();
        byte[] data = currentData.array();
        for (start = dataOffset; start < length && fieldSkipped < fieldsToSkip; start++) {
            if (data[start] == 0x00) {
                fieldSkipped++;
            }
        }

        //seek until we hit the next terminator
        int stop;
        for (stop = start; stop < length; stop++) {
            if (data[stop] == 0x00) {
                break;
            }
        }

        if (stop > length)
            stop = length;
        int finalLength = stop - start;
        byte[] retData = new byte[finalLength];
        System.arraycopy(data, start, retData, 0, finalLength);
        return retData;
    }

    public MultiFieldDecoder getEntryDecoder() throws IOException {
        if (decoder == null) {
            if(currentData!=null) {
                decoder = MultiFieldDecoder.wrap(currentData);
            }else
                decoder = MultiFieldDecoder.create();
        }
        if(currentData!=null)
            decoder.seek(currentData.offset() + dataOffset);
        return decoder;
    }

    public boolean seekForward(MultiFieldDecoder decoder, int position) {
    /*
     * Certain fields may contain zeros--in particular, scalar, float, and double types. We need
     * to skip past those zeros without treating them as delimiters. Since we have that information
     * in the index, we can simply decode and throw away the proper type to adjust the offset properly.
     * However, in some cases it's more efficient to skip the count directly, since we may know the
     * byte size already.
     */
        boolean isNull;
        if (bitIndex.isScalarType(position)) {
            isNull = decoder.nextIsNull();
            decoder.skipLong(); //don't need the value, just need to seek past it
        } else if (bitIndex.isFloatType(position)) {
            isNull = decoder.nextIsNullFloat();
            //floats are always 4 bytes, so skip the after delimiter
            decoder.skipFloat();
        } else if (bitIndex.isDoubleType(position)) {
            isNull = decoder.nextIsNullDouble();
            decoder.skipDouble();
        } else {
            isNull = decoder.nextIsNull();
            decoder.skip();
        }
        return isNull;
    }

    public ByteBuffer nextAsBuffer(MultiFieldDecoder decoder, int position) {
        int offset = decoder.offset();
        seekForward(decoder, position);
        int length = decoder.offset() - 1 - offset;
        if (length <= 0) return null;

        return ByteBuffer.wrap(decoder.array(), offset, length);
    }

    public void accumulate(int position, EntryAccumulator accumulator, byte[] buffer, int offset, int length) {
        if (bitIndex.isScalarType(position)) {
            accumulator.addScalar(position, buffer, offset, length);
        } else if (bitIndex.isFloatType(position)) {
            accumulator.addFloat(position, buffer, offset, length);
        } else if (bitIndex.isDoubleType(position))
            accumulator.addDouble(position, buffer, offset, length);
        else
            accumulator.add(position, buffer, offset, length);
    }

    public void close() {
        if (decoder != null)
            decoder.close();
    }

    public long length() {
        return currentData.length();
    }

    public void nextField(MultiFieldDecoder mutationDecoder, int indexPosition, ByteSlice rowSlice) {
        int offset = mutationDecoder.offset();
        seekForward(mutationDecoder, indexPosition);
        int length = mutationDecoder.offset() - 1 - offset;
        if (length <= 0) return;

        rowSlice.set(mutationDecoder.array(), offset, length);
    }

    @Override
    public MultiFieldDecoder get() {
        try {
            return getEntryDecoder();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
