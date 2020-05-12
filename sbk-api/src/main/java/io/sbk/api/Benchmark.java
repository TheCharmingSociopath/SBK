/**
 * Copyright (c) KMG. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.sbk.api;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public interface Benchmark {

    /**
     * Start the Benchmark.
     *
     * @param startTime start time.
     * @param  secondsToRun number of seconds to Run
     * @param records Maximum number of records to count.If this value 0 or less than 0,then runs till secondsToRun.
     * @return CompletableFuture.
     * @throws IllegalStateException If an exception occurred.
     * @throws IOException If an exception occurred.
     */
    CompletableFuture<Void> start(long startTime, int secondsToRun, int records) throws IOException, IllegalStateException;

    /**
     * stop/shutdown the Benchmark.
     *
     * @param endTime End time
     */
    void stop(long endTime);
}