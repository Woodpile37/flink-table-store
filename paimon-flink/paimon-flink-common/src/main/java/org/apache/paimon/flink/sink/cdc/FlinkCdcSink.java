/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.sink.cdc;

import org.apache.paimon.flink.sink.Committable;
import org.apache.paimon.flink.sink.FlinkSink;
import org.apache.paimon.flink.sink.FlinkWriteSink;
import org.apache.paimon.flink.sink.StoreSinkWrite;
import org.apache.paimon.table.FileStoreTable;

import org.apache.flink.streaming.api.operators.OneInputStreamOperator;

/**
 * A {@link FlinkSink} which accepts {@link CdcRecord} and waits for a schema change if necessary.
 */
public class FlinkCdcSink extends FlinkWriteSink<CdcRecord> {

    private static final long serialVersionUID = 1L;

    public FlinkCdcSink(FileStoreTable table) {
        super(table, null);
    }

    @Override
    protected OneInputStreamOperator<CdcRecord, Committable> createWriteOperator(
            StoreSinkWrite.Provider writeProvider, boolean isStreaming, String commitUser) {
        return new CdcRecordStoreWriteOperator(table, writeProvider, commitUser);
    }
}