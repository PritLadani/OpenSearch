/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.transport;

import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.bytes.CompositeBytesReference;
import org.opensearch.common.bytes.ReleasableBytesReference;
import org.opensearch.common.compress.CompressorFactory;
import org.opensearch.common.io.Streams;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.io.stream.OutputStreamStreamOutput;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.core.common.lease.Releasables;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.io.OutputStream;

public class TransportDecompressorTests extends OpenSearchTestCase {

    public void testSimpleCompression() throws IOException {
        try (BytesStreamOutput output = new BytesStreamOutput()) {
            byte randomByte = randomByte();
            try (OutputStream deflateStream = CompressorFactory.COMPRESSOR.threadLocalOutputStream(Streams.flushOnCloseStream(output))) {
                deflateStream.write(randomByte);
            }

            BytesReference bytes = output.bytes();

            TransportDecompressor decompressor = new TransportDecompressor(PageCacheRecycler.NON_RECYCLING_INSTANCE);
            int bytesConsumed = decompressor.decompress(bytes);
            assertEquals(bytes.length(), bytesConsumed);
            assertTrue(decompressor.isEOS());
            ReleasableBytesReference releasableBytesReference = decompressor.pollDecompressedPage();
            assertEquals(randomByte, releasableBytesReference.get(0));
            releasableBytesReference.close();

        }
    }

    public void testMultiPageCompression() throws IOException {
        try (BytesStreamOutput output = new BytesStreamOutput()) {
            try (
                StreamOutput deflateStream = new OutputStreamStreamOutput(
                    CompressorFactory.COMPRESSOR.threadLocalOutputStream(Streams.flushOnCloseStream(output))
                )
            ) {
                for (int i = 0; i < 10000; ++i) {
                    deflateStream.writeInt(i);
                }
            }

            BytesReference bytes = output.bytes();

            TransportDecompressor decompressor = new TransportDecompressor(PageCacheRecycler.NON_RECYCLING_INSTANCE);
            int bytesConsumed = decompressor.decompress(bytes);
            assertEquals(bytes.length(), bytesConsumed);
            assertTrue(decompressor.isEOS());
            ReleasableBytesReference reference1 = decompressor.pollDecompressedPage();
            ReleasableBytesReference reference2 = decompressor.pollDecompressedPage();
            ReleasableBytesReference reference3 = decompressor.pollDecompressedPage();
            assertNull(decompressor.pollDecompressedPage());
            BytesReference composite = CompositeBytesReference.of(reference1, reference2, reference3);
            assertEquals(4 * 10000, composite.length());
            StreamInput streamInput = composite.streamInput();
            for (int i = 0; i < 10000; ++i) {
                assertEquals(i, streamInput.readInt());
            }
            Releasables.close(reference1, reference2, reference3);
        }
    }

    public void testIncrementalMultiPageCompression() throws IOException {
        try (BytesStreamOutput output = new BytesStreamOutput()) {
            try (
                StreamOutput deflateStream = new OutputStreamStreamOutput(
                    CompressorFactory.COMPRESSOR.threadLocalOutputStream(Streams.flushOnCloseStream(output))
                )
            ) {
                for (int i = 0; i < 10000; ++i) {
                    deflateStream.writeInt(i);
                }
            }

            BytesReference bytes = output.bytes();

            TransportDecompressor decompressor = new TransportDecompressor(PageCacheRecycler.NON_RECYCLING_INSTANCE);

            int split1 = (int) (bytes.length() * 0.3);
            int split2 = (int) (bytes.length() * 0.65);
            BytesReference inbound1 = bytes.slice(0, split1);
            BytesReference inbound2 = bytes.slice(split1, split2 - split1);
            BytesReference inbound3 = bytes.slice(split2, bytes.length() - split2);

            int bytesConsumed1 = decompressor.decompress(inbound1);
            assertEquals(inbound1.length(), bytesConsumed1);
            assertFalse(decompressor.isEOS());
            int bytesConsumed2 = decompressor.decompress(inbound2);
            assertEquals(inbound2.length(), bytesConsumed2);
            assertFalse(decompressor.isEOS());
            int bytesConsumed3 = decompressor.decompress(inbound3);
            assertEquals(inbound3.length(), bytesConsumed3);
            assertTrue(decompressor.isEOS());
            ReleasableBytesReference reference1 = decompressor.pollDecompressedPage();
            ReleasableBytesReference reference2 = decompressor.pollDecompressedPage();
            ReleasableBytesReference reference3 = decompressor.pollDecompressedPage();
            assertNull(decompressor.pollDecompressedPage());
            BytesReference composite = CompositeBytesReference.of(reference1, reference2, reference3);
            assertEquals(4 * 10000, composite.length());
            StreamInput streamInput = composite.streamInput();
            for (int i = 0; i < 10000; ++i) {
                assertEquals(i, streamInput.readInt());
            }
            Releasables.close(reference1, reference2, reference3);

        }
    }

}
