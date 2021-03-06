/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.  You may obtain a
 * copy of the License at <p> http://www.apache.org/licenses/LICENSE-2.0 <p> Unless required by
 * applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.streams.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.integration.utils.EmbeddedKafkaCluster;
import org.apache.kafka.streams.integration.utils.IntegrationTestUtils;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Reducer;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.test.MockKeyValueMapper;
import org.apache.kafka.test.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class KStreamAggregationIntegrationTest {
    private static final int NUM_BROKERS = 1;
    @ClassRule
    public static final EmbeddedKafkaCluster CLUSTER =
        new EmbeddedKafkaCluster(NUM_BROKERS);
    private static volatile int testNo = 0;
    private KStreamBuilder builder;
    private Properties streamsConfiguration;
    private KafkaStreams kafkaStreams;
    private String streamOneInput;
    private String outputTopic;
    private KGroupedStream<String, String> groupedStream;
    private Reducer<String> reducer;
    private Initializer<Integer> initializer;
    private Aggregator<String, String, Integer> aggregator;
    private KStream<Integer, String> stream;


    @Before
    public void before() {
        testNo++;
        builder = new KStreamBuilder();
        createTopics();
        streamsConfiguration = new Properties();
        String applicationId = "kgrouped-stream-test-" +
                       testNo;
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        streamsConfiguration
            .put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, CLUSTER.zKConnectString());
        streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getPath());

        KeyValueMapper<Integer, String, String> mapper = MockKeyValueMapper.<Integer, String>SelectValueMapper();
        stream = builder.stream(Serdes.Integer(), Serdes.String(), streamOneInput);
        groupedStream = stream
            .groupBy(
                mapper,
                Serdes.String(),
                Serdes.String());

        reducer = new Reducer<String>() {
            @Override
            public String apply(String value1, String value2) {
                return value1 + ":" + value2;
            }
        };
        initializer = new Initializer<Integer>() {
            @Override
            public Integer apply() {
                return 0;
            }
        };
        aggregator = new Aggregator<String, String, Integer>() {
            @Override
            public Integer apply(String aggKey, String value, Integer aggregate) {
                return aggregate + value.length();
            }
        };
    }

    @After
    public void whenShuttingDown() throws IOException {
        if (kafkaStreams != null) {
            kafkaStreams.close();
        }
        IntegrationTestUtils.purgeLocalStreamsState(streamsConfiguration);
    }


    @Test
    public void shouldReduce() throws Exception {
        produceMessages(System.currentTimeMillis());
        groupedStream
            .reduce(reducer, "reduce-by-key")
            .to(Serdes.String(), Serdes.String(), outputTopic);

        startStreams();

        produceMessages(System.currentTimeMillis());

        List<KeyValue<String, String>> results = receiveMessages(
            new StringDeserializer(),
            new StringDeserializer()
            , 10);

        Collections.sort(results, new Comparator<KeyValue<String, String>>() {
            @Override
            public int compare(KeyValue<String, String> o1, KeyValue<String, String> o2) {
                return KStreamAggregationIntegrationTest.compare(o1, o2);
            }
        });

        assertThat(results, is(Arrays.asList(KeyValue.pair("A", "A"),
                                             KeyValue.pair("A", "A:A"),
                                             KeyValue.pair("B", "B"),
                                             KeyValue.pair("B", "B:B"),
                                             KeyValue.pair("C", "C"),
                                             KeyValue.pair("C", "C:C"),
                                             KeyValue.pair("D", "D"),
                                             KeyValue.pair("D", "D:D"),
                                             KeyValue.pair("E", "E"),
                                             KeyValue.pair("E", "E:E"))));
    }

    @SuppressWarnings("unchecked")
    private static <K extends Comparable, V extends Comparable> int compare(final KeyValue<K, V> o1,
                                                                            final KeyValue<K, V> o2) {
        final int keyComparison = o1.key.compareTo(o2.key);
        if (keyComparison == 0) {
            return o1.value.compareTo(o2.value);
        }
        return keyComparison;
    }

    @Test
    public void shouldReduceWindowed() throws Exception {
        long firstBatchTimestamp = System.currentTimeMillis() - 1000;
        produceMessages(firstBatchTimestamp);
        long secondBatchTimestamp = System.currentTimeMillis();
        produceMessages(secondBatchTimestamp);
        produceMessages(secondBatchTimestamp);

        groupedStream
            .reduce(reducer, TimeWindows.of(500L), "reduce-time-windows")
            .toStream(new KeyValueMapper<Windowed<String>, String, String>() {
                @Override
                public String apply(Windowed<String> windowedKey, String value) {
                    return windowedKey.key() + "@" + windowedKey.window().start();
                }
            })
            .to(Serdes.String(), Serdes.String(), outputTopic);

        startStreams();

        List<KeyValue<String, String>> windowedOutput = receiveMessages(
            new StringDeserializer(),
            new StringDeserializer()
            , 15);

        Comparator<KeyValue<String, String>>
            comparator =
            new Comparator<KeyValue<String, String>>() {
                @Override
                public int compare(final KeyValue<String, String> o1,
                                   final KeyValue<String, String> o2) {
                    return KStreamAggregationIntegrationTest.compare(o1, o2);
                }
            };

        Collections.sort(windowedOutput, comparator);
        long firstBatchWindow = firstBatchTimestamp / 500 * 500;
        long secondBatchWindow = secondBatchTimestamp / 500 * 500;

        assertThat(windowedOutput, is(
            Arrays.asList(
                new KeyValue<>("A@" + firstBatchWindow, "A"),
                new KeyValue<>("A@" + secondBatchWindow, "A"),
                new KeyValue<>("A@" + secondBatchWindow, "A:A"),
                new KeyValue<>("B@" + firstBatchWindow, "B"),
                new KeyValue<>("B@" + secondBatchWindow, "B"),
                new KeyValue<>("B@" + secondBatchWindow, "B:B"),
                new KeyValue<>("C@" + firstBatchWindow, "C"),
                new KeyValue<>("C@" + secondBatchWindow, "C"),
                new KeyValue<>("C@" + secondBatchWindow, "C:C"),
                new KeyValue<>("D@" + firstBatchWindow, "D"),
                new KeyValue<>("D@" + secondBatchWindow, "D"),
                new KeyValue<>("D@" + secondBatchWindow, "D:D"),
                new KeyValue<>("E@" + firstBatchWindow, "E"),
                new KeyValue<>("E@" + secondBatchWindow, "E"),
                new KeyValue<>("E@" + secondBatchWindow, "E:E")
            )
        ));
    }

    @Test
    public void shouldAggregate() throws Exception {
        produceMessages(System.currentTimeMillis());
        groupedStream.aggregate(
            initializer,
            aggregator,
            Serdes.Integer(),
            "aggregate-by-selected-key")
            .to(Serdes.String(), Serdes.Integer(), outputTopic);

        startStreams();

        produceMessages(System.currentTimeMillis());

        List<KeyValue<String, Integer>> results = receiveMessages(
            new StringDeserializer(),
            new IntegerDeserializer()
            , 10);

        Collections.sort(results, new Comparator<KeyValue<String, Integer>>() {
            @Override
            public int compare(KeyValue<String, Integer> o1, KeyValue<String, Integer> o2) {
                return KStreamAggregationIntegrationTest.compare(o1, o2);
            }
        });

        assertThat(results, is(Arrays.asList(
            KeyValue.pair("A", 1),
            KeyValue.pair("A", 2),
            KeyValue.pair("B", 1),
            KeyValue.pair("B", 2),
            KeyValue.pair("C", 1),
            KeyValue.pair("C", 2),
            KeyValue.pair("D", 1),
            KeyValue.pair("D", 2),
            KeyValue.pair("E", 1),
            KeyValue.pair("E", 2)
        )));
    }

    @Test
    public void shouldAggregateWindowed() throws Exception {
        long firstTimestamp = System.currentTimeMillis() - 1000;
        produceMessages(firstTimestamp);
        long secondTimestamp = System.currentTimeMillis();
        produceMessages(secondTimestamp);
        produceMessages(secondTimestamp);

        groupedStream.aggregate(
            initializer,
            aggregator,
            TimeWindows.of(500L),
            Serdes.Integer(), "aggregate-by-key-windowed")
            .toStream(new KeyValueMapper<Windowed<String>, Integer, String>() {
                @Override
                public String apply(Windowed<String> windowedKey, Integer value) {
                    return windowedKey.key() + "@" + windowedKey.window().start();
                }
            })
            .to(Serdes.String(), Serdes.Integer(), outputTopic);

        startStreams();

        List<KeyValue<String, Integer>> windowedMessages = receiveMessages(
            new StringDeserializer(),
            new IntegerDeserializer()
            , 15);

        Comparator<KeyValue<String, Integer>>
            comparator =
            new Comparator<KeyValue<String, Integer>>() {
                @Override
                public int compare(final KeyValue<String, Integer> o1,
                                   final KeyValue<String, Integer> o2) {
                    return KStreamAggregationIntegrationTest.compare(o1, o2);
                }
            };

        Collections.sort(windowedMessages, comparator);

        long firstWindow = firstTimestamp / 500 * 500;
        long secondWindow = secondTimestamp / 500 * 500;

        assertThat(windowedMessages, is(
            Arrays.asList(
                new KeyValue<>("A@" + firstWindow, 1),
                new KeyValue<>("A@" + secondWindow, 1),
                new KeyValue<>("A@" + secondWindow, 2),
                new KeyValue<>("B@" + firstWindow, 1),
                new KeyValue<>("B@" + secondWindow, 1),
                new KeyValue<>("B@" + secondWindow, 2),
                new KeyValue<>("C@" + firstWindow, 1),
                new KeyValue<>("C@" + secondWindow, 1),
                new KeyValue<>("C@" + secondWindow, 2),
                new KeyValue<>("D@" + firstWindow, 1),
                new KeyValue<>("D@" + secondWindow, 1),
                new KeyValue<>("D@" + secondWindow, 2),
                new KeyValue<>("E@" + firstWindow, 1),
                new KeyValue<>("E@" + secondWindow, 1),
                new KeyValue<>("E@" + secondWindow, 2)
            )));
    }

    @Test
    public void shouldCount() throws Exception {
        produceMessages(System.currentTimeMillis());

        groupedStream.count("count-by-key")
            .to(Serdes.String(), Serdes.Long(), outputTopic);

        startStreams();

        produceMessages(System.currentTimeMillis());

        List<KeyValue<String, Long>> results = receiveMessages(
            new StringDeserializer(),
            new LongDeserializer()
            , 10);
        Collections.sort(results, new Comparator<KeyValue<String, Long>>() {
            @Override
            public int compare(KeyValue<String, Long> o1, KeyValue<String, Long> o2) {
                return KStreamAggregationIntegrationTest.compare(o1, o2);
            }
        });

        assertThat(results, is(Arrays.asList(
            KeyValue.pair("A", 1L),
            KeyValue.pair("A", 2L),
            KeyValue.pair("B", 1L),
            KeyValue.pair("B", 2L),
            KeyValue.pair("C", 1L),
            KeyValue.pair("C", 2L),
            KeyValue.pair("D", 1L),
            KeyValue.pair("D", 2L),
            KeyValue.pair("E", 1L),
            KeyValue.pair("E", 2L)
        )));
    }

    @Test
    public void shouldGroupByKey() throws Exception {
        long timestamp = System.currentTimeMillis();
        produceMessages(timestamp);
        produceMessages(timestamp);

        stream.groupByKey(Serdes.Integer(), Serdes.String())
            .count(TimeWindows.of(500L), "count-windows")
            .toStream(new KeyValueMapper<Windowed<Integer>, Long, String>() {
                @Override
                public String apply(final Windowed<Integer> windowedKey, final Long value) {
                    return windowedKey.key() + "@" + windowedKey.window().start();
                }
            }).to(Serdes.String(), Serdes.Long(), outputTopic);

        startStreams();

        List<KeyValue<String, Long>> results = receiveMessages(
            new StringDeserializer(),
            new LongDeserializer()
            , 10);
        Collections.sort(results, new Comparator<KeyValue<String, Long>>() {
            @Override
            public int compare(KeyValue<String, Long> o1, KeyValue<String, Long> o2) {
                return KStreamAggregationIntegrationTest.compare(o1, o2);
            }
        });

        long window = timestamp / 500 * 500;
        assertThat(results, is(Arrays.asList(
            KeyValue.pair("1@" + window, 1L),
            KeyValue.pair("1@" + window, 2L),
            KeyValue.pair("2@" + window, 1L),
            KeyValue.pair("2@" + window, 2L),
            KeyValue.pair("3@" + window, 1L),
            KeyValue.pair("3@" + window, 2L),
            KeyValue.pair("4@" + window, 1L),
            KeyValue.pair("4@" + window, 2L),
            KeyValue.pair("5@" + window, 1L),
            KeyValue.pair("5@" + window, 2L)
        )));

    }


    private void produceMessages(long timestamp)
        throws ExecutionException, InterruptedException {
        IntegrationTestUtils.produceKeyValuesSynchronouslyWithTimestamp(
            streamOneInput,
            Arrays.asList(
                new KeyValue<>(1, "A"),
                new KeyValue<>(2, "B"),
                new KeyValue<>(3, "C"),
                new KeyValue<>(4, "D"),
                new KeyValue<>(5, "E")),
            TestUtils.producerConfig(
                CLUSTER.bootstrapServers(),
                IntegerSerializer.class,
                StringSerializer.class,
                new Properties()),
            timestamp);
    }


    private void createTopics() {
        streamOneInput = "stream-one-" + testNo;
        outputTopic = "output-" + testNo;
        CLUSTER.createTopic(streamOneInput, 3, 1);
        CLUSTER.createTopic(outputTopic);
    }

    private void startStreams() {
        kafkaStreams = new KafkaStreams(builder, streamsConfiguration);
        kafkaStreams.start();
    }


    private <K, V> List<KeyValue<K, V>> receiveMessages(final Deserializer<K>
                                                            keyDeserializer,
                                                        final Deserializer<V>
                                                            valueDeserializer,
                                                        final int numMessages)
        throws InterruptedException {
        final Properties consumerProperties = new Properties();
        consumerProperties
            .setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
        consumerProperties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "kgroupedstream-test-" +
                                                                       testNo);
        consumerProperties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                       keyDeserializer.getClass().getName());
        consumerProperties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                       valueDeserializer.getClass().getName());
        return IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerProperties,
                                                                        outputTopic,
                                                                        numMessages, 60 * 1000);

    }

}
