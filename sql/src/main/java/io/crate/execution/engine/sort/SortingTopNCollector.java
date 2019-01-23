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

package io.crate.execution.engine.sort;

import com.google.common.base.Preconditions;
import io.crate.data.ArrayBucket;
import io.crate.data.Bucket;
import io.crate.data.Input;
import io.crate.data.Row;
import io.crate.execution.engine.collect.CollectExpression;
import org.apache.lucene.util.ArrayUtil;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;


/**
 * Collector implementation which collects rows into a priorityQueue in order to sort the rows and apply a limit + offset.
 * The final result is a sorted bucket with limit and offset applied.
 */
public class SortingTopNCollector implements Collector<Row, RowsQueue, Bucket> {

    private final int NUMBER_OF_ROWS_IN_BOUNDED_QUEUE_THRESHOLD = 10_000;

    private final Collection<? extends Input<?>> inputs;
    private final Iterable<? extends CollectExpression<Row, ?>> expressions;
    private final int numOutputs;
    private final Comparator<Object[]> comparator;
    private final int offset;
    private final int maxNumberOfRowsInQueue;
    private final Supplier<RowsQueue> sortingContainerSupplier;

    /**
     * @param inputs      contains output {@link Input}s and orderBy {@link Input}s
     * @param expressions expressions linked to the inputs
     * @param numOutputs  number of output columns
     * @param comparator  used to sort the rows
     * @param limit       the max number of rows the result should contain
     * @param offset      the number of rows to skip (after sort)
     */
    public SortingTopNCollector(Collection<? extends Input<?>> inputs,
                                Iterable<? extends CollectExpression<Row, ?>> expressions,
                                int numOutputs,
                                Comparator<Object[]> comparator,
                                int limit,
                                int offset) {
        Preconditions.checkArgument(limit > 0, "Invalid LIMIT: value must be > 0; got: " + limit);
        Preconditions.checkArgument(offset >= 0, "Invalid OFFSET: value must be >= 0; got: " + offset);

        this.inputs = inputs;
        this.expressions = expressions;
        this.numOutputs = numOutputs;
        this.comparator = comparator;
        this.offset = offset;
        int rowsToProcess = limit + offset;

        if (rowsToProcess >= ArrayUtil.MAX_ARRAY_LENGTH || rowsToProcess < 0) {
            // Throw exception to prevent confusing OOME in PriorityQueue
            // 1) if offset + limit exceeds maximum array length
            // 2) if offset + limit exceeds Integer.MAX_VALUE (then maxSize is negative!)
            throw new IllegalArgumentException(
                "Invalid LIMIT + OFFSET: value must be <= " + (ArrayUtil.MAX_ARRAY_LENGTH - 1) + "; got: " +
                rowsToProcess);
        }

        /**
         * We'll use an unbounded queue with the initial capacity of {@link NUMBER_OF_ROWS_IN_BOUNDED_QUEUE_THRESHOLD}
         * if the maximum number of rows we have to accomodate in the queue in order to provide the correct result is
         * greater than this threshold.
         *
         * Otherwise, we'll use a bounded queue with a capacity of {@link NUMBER_OF_ROWS_IN_BOUNDED_QUEUE_THRESHOLD}
         */
        if (rowsToProcess > NUMBER_OF_ROWS_IN_BOUNDED_QUEUE_THRESHOLD) {
            maxNumberOfRowsInQueue = NUMBER_OF_ROWS_IN_BOUNDED_QUEUE_THRESHOLD;
            sortingContainerSupplier = () -> unboundedRowsQueue(comparator);
        } else {
            maxNumberOfRowsInQueue = rowsToProcess;
            sortingContainerSupplier = boundedRowsQueue(comparator);
        }
    }

    private Supplier<RowsQueue> boundedRowsQueue(Comparator<Object[]> comparator) {
        return () -> new RowsQueue() {

            private final BoundedPriorityQueue<Object[]> priorityQueue =
                new BoundedPriorityQueue<>(maxNumberOfRowsInQueue, comparator);

            @Override
            public void add(Object[] e) {
                priorityQueue.insertWithOverflow(e);
            }

            @Override
            public Object[] pop() {
                return priorityQueue.pop();
            }

            @Nullable
            @Override
            public Object[] peek() {
                return priorityQueue.top();
            }

            @Override
            public int size() {
                return priorityQueue.size();
            }
        };
    }

    private RowsQueue unboundedRowsQueue(Comparator<Object[]> comparator) {
        return new RowsQueue() {

            private PriorityQueue<Object[]> priorityQueue = new PriorityQueue<>(maxNumberOfRowsInQueue, comparator);

            @Override
            public void add(Object[] e) {
                priorityQueue.add(e);
            }

            @Override
            public Object[] pop() {
                return priorityQueue.poll();
            }

            @Override
            public Object[] peek() {
                return priorityQueue.peek();
            }

            @Override
            public int size() {
                return priorityQueue.size();
            }
        };
    }

    @Override
    public Supplier<RowsQueue> supplier() {
        return sortingContainerSupplier;
    }

    @Override
    public BiConsumer<RowsQueue, Row> accumulator() {
        return this::onNextRow;
    }

    @Override
    public BinaryOperator<RowsQueue> combiner() {
        return (pq1, pq2) -> {
            throw new UnsupportedOperationException("combine not supported");
        };
    }

    @Override
    public Function<RowsQueue, Bucket> finisher() {
        return this::pqToIterable;
    }

    @Override
    public Set<Characteristics> characteristics() {
        return Collections.emptySet();
    }

    private void onNextRow(RowsQueue pq, Row row) {
        for (CollectExpression<Row, ?> expression : expressions) {
            expression.setNextRow(row);
        }
        Object[] rowCells = new Object[inputs.size()];
        int i = 0;
        for (Input<?> input : inputs) {
            rowCells[i] = input.value();
            i++;
        }

        if (pq.size() == maxNumberOfRowsInQueue) {
            Object[] highestElementInOrder = pq.peek();
            if (highestElementInOrder == null || comparator.compare(rowCells, highestElementInOrder) > 0) {
                pq.pop();
                pq.add(rowCells);
            }
        } else {
            pq.add(rowCells);
        }
    }

    private Bucket pqToIterable(RowsQueue pq) {
        if (offset > pq.size()) {
            return new ArrayBucket(new Object[0][0], numOutputs);
        }
        int resultSize = Math.max(Math.min(maxNumberOfRowsInQueue - offset, pq.size() - offset), 0);

        Object[][] rows = new Object[resultSize][];
        for (int i = resultSize - 1; i >= 0; i--) {
            rows[i] = pq.pop();
        }
        return new ArrayBucket(rows, numOutputs);
    }

    private static class BoundedPriorityQueue<T> extends org.apache.lucene.util.PriorityQueue<T> {

        private final Comparator<T> comparator;

        private BoundedPriorityQueue(int maxSize, Comparator<T> comparator) {
            super(maxSize);
            this.comparator = comparator;
        }

        @Override
        public boolean lessThan(T a, T b) {
            return comparator.compare(a, b) < 0;
        }
    }

}
