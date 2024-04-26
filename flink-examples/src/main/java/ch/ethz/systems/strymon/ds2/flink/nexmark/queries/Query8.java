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

package ch.ethz.systems.strymon.ds2.flink.nexmark.queries;

import ch.ethz.systems.strymon.ds2.flink.nexmark.sinks.DummyLatencyCountingSink;
import ch.ethz.systems.strymon.ds2.flink.nexmark.sources.AuctionSourceFunction;
import ch.ethz.systems.strymon.ds2.flink.nexmark.sources.KafkaGenericSourceFunction;
import ch.ethz.systems.strymon.ds2.flink.nexmark.sources.PersonSourceFunction;
import org.apache.beam.sdk.nexmark.model.Auction;
import org.apache.beam.sdk.nexmark.model.Bid;
import org.apache.beam.sdk.nexmark.model.Person;
import org.apache.flink.api.common.functions.FlatJoinFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

public class Query8 {

    private static final Logger logger  = LoggerFactory.getLogger(Query8.class);

    public static void main(String[] args) throws Exception {

        // Checking input parameters
        final ParameterTool params = ParameterTool.fromArgs(args);
        final int auctionSrcRate = params.getInt("auction-srcRate", 50000);

        final int personSrcRate = params.getInt("person-srcRate", 30000);

        StreamExecutionEnvironment env;
        String remoteAddress = params.get("jobmanager.rpc.address");
        if (remoteAddress == null) {
            env = StreamExecutionEnvironment.getExecutionEnvironment();
        } else {
            env = StreamExecutionEnvironment.createRemoteEnvironment(remoteAddress.split(":")[0], Integer.parseInt(remoteAddress.split(":")[1]), "flink-examples/target/flink-examples-1.0-SNAPSHOT.jar");
        }
        String kafkaAddress = params.get("kafkaAddress", "kafka-edge1:9092,localhost:9094");
        
        RichParallelSourceFunction<Bid> bidSource;
        RichParallelSourceFunction<Auction> auctionSource;
        RichParallelSourceFunction<Person> personSource;
        
        String sourceName;
        if (kafkaAddress.isEmpty()) {
            //bidSource = new BidSourceFunction(srcRate);
            auctionSource = new AuctionSourceFunction(auctionSrcRate);
            personSource = new PersonSourceFunction(personSrcRate);
            sourceName = "Task generator - %s";
        } else {
            //bidSource = new KafkaGenericSourceFunction<Bid>(Bid.class, kafkaAddress, "bid", "bid");
            auctionSource = new KafkaGenericSourceFunction<>(Auction.class, kafkaAddress, "auction", "auction");
            personSource = new KafkaGenericSourceFunction<>(Person.class, kafkaAddress, "person", "person");
            sourceName = "Kafka generator - %s";
        }

        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        env.getConfig().setAutoWatermarkInterval(1000);

        // enable latency tracking
        //env.getConfig().setLatencyTrackingInterval(5000);



        env.setParallelism(params.getInt("p-window", 1));

        DataStream<Person> persons = env.addSource(personSource, String.format(sourceName, "person"), TypeInformation.of(Person.class))
                .name("Custom Source: Persons")
                .setParallelism(params.getInt("p-person-source", 1))
                .assignTimestampsAndWatermarks(new PersonTimestampAssigner());

        DataStream<Auction> auctions = env.addSource(auctionSource, String.format(sourceName, "auction"), TypeInformation.of(Auction.class))
                .name("Custom Source: Auctions")
                .setParallelism(params.getInt("p-auction-source", 1))
                .assignTimestampsAndWatermarks(new AuctionTimestampAssigner());

        // SELECT Rstream(P.id, P.name, A.reserve)
        // FROM Person [RANGE 1 HOUR] P, Auction [RANGE 1 HOUR] A
        // WHERE P.id = A.seller;
        DataStream<Tuple3<Long, String, Long>> joined =
                persons.join(auctions)
                .where(new KeySelector<Person, Long>() {
                    @Override
                    public Long getKey(Person p) {
                        return p.id;
                    }
                }).equalTo(new KeySelector<Auction, Long>() {
                            @Override
                            public Long getKey(Auction a) {
                                return a.seller;
                            }
                        })
                .window(TumblingEventTimeWindows.of(Time.seconds(10)))
                .apply(new FlatJoinFunction<Person, Auction, Tuple3<Long, String, Long>>() {
                    @Override
                    public void join(Person p, Auction a, Collector<Tuple3<Long, String, Long>> out) {
                        out.collect(new Tuple3<>(p.id, p.name, a.reserve));
                    }
                });


        GenericTypeInfo<Object> objectTypeInfo = new GenericTypeInfo<>(Object.class);
        joined.transform("DummyLatencySink", objectTypeInfo, new DummyLatencyCountingSink<>(logger))
                .setParallelism(params.getInt("p-window", 1));

        // execute program
        env.execute("Nexmark Query8");
    }

    private static final class PersonTimestampAssigner implements AssignerWithPeriodicWatermarks<Person> {
        private long maxTimestamp = Long.MIN_VALUE;

        @Nullable
        @Override
        public Watermark getCurrentWatermark() {
            return new Watermark(maxTimestamp);
        }

        @Override
        public long extractTimestamp(Person element, long previousElementTimestamp) {
            maxTimestamp = Math.max(maxTimestamp, element.dateTime);
            return element.dateTime;
        }
    }

    private static final class AuctionTimestampAssigner implements AssignerWithPeriodicWatermarks<Auction> {
        private long maxTimestamp = Long.MIN_VALUE;

        @Nullable
        @Override
        public Watermark getCurrentWatermark() {
            return new Watermark(maxTimestamp);
        }

        @Override
        public long extractTimestamp(Auction element, long previousElementTimestamp) {
            maxTimestamp = Math.max(maxTimestamp, element.dateTime);
            return element.dateTime;
        }
    }

}