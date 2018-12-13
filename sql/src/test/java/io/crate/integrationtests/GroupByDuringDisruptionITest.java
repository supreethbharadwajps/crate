/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.integrationtests;

import com.carrotsearch.randomizedtesting.annotations.Seed;
import io.crate.action.sql.SQLOperations;
import io.crate.action.sql.Session;
import io.crate.auth.user.User;
import io.crate.exceptions.Exceptions;
import io.crate.testing.SQLResponse;
import io.crate.testing.SQLTransportExecutor;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

@ESIntegTestCase.ClusterScope(minNumDataNodes = 3, maxNumDataNodes = 3, transportClientRatio = 0, numClientNodes = 0)
@Seed("7E9214BE397A0683:F9463174A59FC85D")
public class GroupByDuringDisruptionITest extends SQLTransportIntegrationTest {

    private ExecutorService executorService;
    private AtomicBoolean stopThreads;
    private int numThreads = 50;

    @Before
    public void setupExecutor() throws Exception {
        stopThreads = new AtomicBoolean(false);
        executorService = Executors.newFixedThreadPool(numThreads);
    }

    @After
    public void tearDownExecutor() throws Exception {
        stopThreads.set(true);
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    public void testALotOfQueries() throws Exception {
        execute("create table t1 (x int) clustered into 3 shards with (number_of_replicas = 0)");
        Object[][] bulkArgs = IntStream.concat(
            IntStream.range(0, 24),
            IntStream.range(2, 36))
            .mapToObj(x -> new Object[] { x })
            .toArray(Object[][]::new);
        execute("insert into t1 (x) values (?)", bulkArgs);
        execute("refresh table t1");


        int spawnLimit = 20_000;
        AtomicInteger requestsMade = new AtomicInteger(0);
        final List<ActionFuture<SQLResponse>> responses = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            executorService.submit(() -> {
                while (requestsMade.incrementAndGet() < spawnLimit) {
                    ActionFuture<SQLResponse> resp = sqlExecutor.execute("select x, count(*) from t1 group by x", null);
                    synchronized (responses) {
                        responses.add(resp);
                    }
                }
            });
        }
        executorService.shutdown();
        executorService.awaitTermination(50, TimeUnit.SECONDS);
        for (ActionFuture<SQLResponse> resp : responses) {
            try {
                resp.get(50, TimeUnit.SECONDS);
            } catch (Exception e) {
                String message = Exceptions.userFriendlyMessageInclNested(e);
                if (message.contains("EsRejectedExecutionException")) {
                    // OK
                } else {
                    throw e;
                }
            }
        }
    }

    @Test
    //@TestLogging("io.crate.execution.jobs.transport.NodeDisconnectJobMonitorService:DEBUG,io.crate.execution.jobs.TasksService:TRACE")
    @TestLogging("io.crate:TRACE")
    public void testQueriesFinishSomehowIfNodeIsStopped() throws Exception {
        execute("create table doc.t1 (x int) clustered into 3 shards with (number_of_replicas = 1)");
        Object[][] bulkArgs = IntStream.concat(
            IntStream.range(0, 24),
            IntStream.range(2, 536))
            .mapToObj(x -> new Object[] { x })
            .toArray(Object[][]::new);
        execute("insert into doc.t1 (x) values (?)", bulkArgs);
        execute("refresh table doc.t1");

        String[] nodeNames = internalCluster().getNodeNames();
        String nodeToStop = nodeNames[2];

        final ArrayList<ActionFuture<SQLResponse>> resultFutures = new ArrayList<>();
        int queriesToTriggerBeforeNodeStop = 10;
        CountDownLatch triggered = new CountDownLatch(queriesToTriggerBeforeNodeStop);
        CountDownLatch threadsFinished = new CountDownLatch(numThreads);
        for (int i = 0; i < numThreads; i++) {
            executorService.submit(() -> {
                while (!stopThreads.get()) {
                    String nodeName = nodeNames[randomIntBetween(0, 1)];
                    Session session = internalCluster().getInstance(SQLOperations.class, nodeName)
                        .createSession("doc", User.CRATE_USER);
                    ActionFuture<SQLResponse> futureResult = SQLTransportExecutor.execute(
                        "select x, count(*) from t1 group by x",
                        null,
                        session
                    );
                    synchronized (resultFutures) {
                        resultFutures.add(futureResult);
                    }
                    triggered.countDown();
                }
                threadsFinished.countDown();
            });
        }

        triggered.await();
        logger.info("Triggered at least {} queries", queriesToTriggerBeforeNodeStop);
        try {
            internalCluster().stopRandomNode(s -> Node.NODE_NAME_SETTING.get(s).equals(nodeToStop));
        } catch (AssertionError e) {
            logger.info("AssertionError during node stop", e);
        }
        stopThreads.set(true);
        threadsFinished.await();
        logger.info("Stopped triggering new queries");

        removeCompletedFutures(resultFutures);
        logger.info("Remaining jobs: {}", resultFutures.size());
        assertNoTasksAreLeftOpen();
        removeCompletedFutures(resultFutures);
        logger.info("Remaining jobs after waiting for tasks to complete: {}", resultFutures.size());
        assertThat(resultFutures, Matchers.empty());
    }

    private void removeCompletedFutures(ArrayList<ActionFuture<SQLResponse>> resultFutures) {
        ListIterator<ActionFuture<SQLResponse>> it = resultFutures.listIterator();
        while (it.hasNext()) {
            ActionFuture<SQLResponse> future = it.next();
            try {
                future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
                // we keep them in the list
            } catch (Throwable t) {
                logger.info("Query completed with failure: {}", t.getMessage());
                it.remove();
            }
        }
    }
}
