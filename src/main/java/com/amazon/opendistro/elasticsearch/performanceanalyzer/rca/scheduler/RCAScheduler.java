/*
 * Copyright <2019> Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistro.elasticsearch.performanceanalyzer.rca.scheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RCAScheduler {

    //TODO: Change magic number 4 to something based on a config.
    ScheduledExecutorService scheduledPool = Executors.newScheduledThreadPool(4);

    public void start() {
        //Create scheduledExecutor.
        //Queue tasks and print hello.
        //Implement ticks.
        //Implement multiple tasks scheduled at different ticks.
        //Task executor service with a single queue.
        //Simulation service

        final Runnable runner = new Runnable() {
            public void run() {
                System.out.println("Running");
            }
        };

        scheduledPool.scheduleAtFixedRate(runner, 1, 1, TimeUnit.SECONDS);
    }
}

