/**
 * Copyright (c) KMG. All Rights Reserved..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.sbk.api.impl;

import io.sbk.api.DataType;
import io.sbk.api.Parameters;
import io.sbk.api.RecordTime;
import io.sbk.api.Writer;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;

/**
 * Writer Benchmarking Implementation.
 */
public class SbkWriter extends Worker implements Runnable {
    final private static int MS_PER_SEC = 1000;
    final private DataType data;
    final private Writer writer;
    final private RunBenchmark perf;
    final private Object payload;

    public SbkWriter(int writerID, int idMax, Parameters params, RecordTime recordTime, DataType data, Writer writer) {
        super(writerID, idMax, params, recordTime);
        this.data = data;
        this.writer = writer;
        this.payload = data.create(params.getRecordSize());
        this.perf = createBenchmark();
    }

    @Override
    public void run()  {
        try {
            perf.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private RunBenchmark createBenchmark() {
        final RunBenchmark perfWriter;
        if (params.getSecondsToRun() > 0) {
            if (params.isWriteAndRead()) {
                perfWriter = this::RecordsWriterTimeRW;
            } else {
                if (params.getRecordsPerSec() > 0 || params.getRecordsPerFlush() < Integer.MAX_VALUE) {
                    perfWriter = this::RecordsWriterTimeFlush;
                } else {
                    perfWriter = this::RecordsWriterTime;
                }
            }
        } else {
            if (params.isWriteAndRead()) {
                perfWriter = this::RecordsWriterRW;
            } else {
                if (params.getRecordsPerSec() > 0 || params.getRecordsPerFlush() < Integer.MAX_VALUE) {
                    perfWriter = this::RecordsWriterFlush;
                } else {
                    perfWriter = this::RecordsWriter;
                }
            }
        }
        return perfWriter;
    }


    private void RecordsWriter() throws InterruptedException, IOException {
        final int size = data.length(payload);
        for (int i = 0; i < params.getRecordsPerWriter(); i++) {
            writer.recordWrite(payload, size, recordTime, i % idMax);
        }
        writer.flush();
    }


    private void RecordsWriterFlush() throws InterruptedException, IOException {
        final RateController eCnt = new RateController(System.currentTimeMillis(), params.getRecordsPerSec());
        final int recordsCount = params.getRecordsPerWriter();
        final int size = data.length(payload);
        int cnt = 0;
        while (cnt < recordsCount) {
            int loopMax = Math.min(params.getRecordsPerFlush(), recordsCount - cnt);
            for (int i = 0; i < loopMax; i++) {
                eCnt.control(cnt++, writer.recordWrite(payload, size, recordTime, i % idMax));
            }
            writer.flush();
        }
    }


    private void RecordsWriterTime() throws InterruptedException, IOException {
        final long startTime = params.getStartTime();
        final long msToRun = params.getSecondsToRun() * MS_PER_SEC;
        final int size = data.length(payload);
        long time = System.currentTimeMillis();
        int id = workerID % idMax;
        while ((time - startTime) < msToRun) {
            time = writer.recordWrite(payload, size, recordTime, id);
            id += 1;
            if (id >= idMax) {
                id = 0;
            }
        }
        writer.flush();
    }


    private void RecordsWriterTimeFlush() throws InterruptedException, IOException {
        final long startTime = params.getStartTime();
        final long msToRun = params.getSecondsToRun() * MS_PER_SEC;
        final int size = data.length(payload);
        long time = System.currentTimeMillis();
        final RateController eCnt = new RateController(time, params.getRecordsPerSec());
        long msElapsed = time - startTime;
        int cnt = 0;
        while (msElapsed < msToRun) {
            for (int i = 0; (msElapsed < msToRun) && (i < params.getRecordsPerFlush()); i++) {
                time = writer.recordWrite(payload, size, recordTime, i % idMax);
                eCnt.control(cnt++, time);
                msElapsed = time - startTime;
            }
            writer.flush();
        }
    }


    private void RecordsWriterRW() throws InterruptedException, IOException {
        final RateController eCnt = new RateController(System.currentTimeMillis(), params.getRecordsPerSec());
        final int recordsCount = params.getRecordsPerWriter();
        int cnt = 0;
        long time;
        while (cnt < recordsCount) {
            int loopMax = Math.min(params.getRecordsPerFlush(), recordsCount - cnt);
            for (int i = 0; i < loopMax; i++) {
                time = writer.writeAsyncTime(data, payload);
                eCnt.control(cnt++, time);
            }
            writer.flush();
        }
    }


    private void RecordsWriterTimeRW() throws InterruptedException, IOException {
        final long startTime = params.getStartTime();
        final long msToRun = params.getSecondsToRun() * MS_PER_SEC;
        long time = System.currentTimeMillis();
        final RateController eCnt = new RateController(time, params.getRecordsPerSec());
        long msElapsed = time - startTime;
        int cnt = 0;
        while (msElapsed < msToRun) {
            for (int i = 0; (msElapsed < msToRun) && (i < params.getRecordsPerFlush()); i++) {
                time = writer.writeAsyncTime(data, payload);
                eCnt.control(cnt++, time);
                msElapsed = time - startTime;
            }
            writer.flush();
        }
    }


    @NotThreadSafe
    final static private class RateController {
        private static final long NS_PER_MS = 1000000L;
        private static final long NS_PER_SEC = 1000 * NS_PER_MS;
        private static final long MIN_SLEEP_NS = 2 * NS_PER_MS;
        private final long startTime;
        private final long sleepTimeNs;
        private final int recordsPerSec;
        private long toSleepNs = 0;

        /**
         * @param recordsPerSec events per second
         */
        private RateController(long start, int recordsPerSec) {
            this.startTime = start;
            this.recordsPerSec = recordsPerSec;
            this.sleepTimeNs = this.recordsPerSec > 0 ?
                    NS_PER_SEC / this.recordsPerSec : 0;
        }

        /**
         * Blocks for small amounts of time to achieve targetThroughput/events per sec
         *
         * @param events current events
         * @param time   current time
         */
        void control(long events, long time) {
            if (this.recordsPerSec <= 0) {
                return;
            }
            needSleep(events, time);
        }

        private void needSleep(long events, long time) {
            float elapsedSec = (time - startTime) / 1000.f;

            if ((events / elapsedSec) < this.recordsPerSec) {
                return;
            }

            // control throughput / number of events by sleeping, on average,
            toSleepNs += sleepTimeNs;
            // If threshold reached, sleep a little
            if (toSleepNs >= MIN_SLEEP_NS) {
                long sleepStart = System.nanoTime();
                try {
                    final long sleepMs = toSleepNs / NS_PER_MS;
                    final long sleepNs = toSleepNs - sleepMs * NS_PER_MS;
                    Thread.sleep(sleepMs, (int) sleepNs);
                } catch (InterruptedException e) {
                    // will be taken care in finally block
                } finally {
                    // in case of short sleeps or oversleep ;adjust it for next sleep duration
                    final long sleptNS = System.nanoTime() - sleepStart;
                    if (sleptNS > 0) {
                        toSleepNs -= sleptNS;
                    } else {
                        toSleepNs = 0;
                    }
                }
            }
        }
    }
}
