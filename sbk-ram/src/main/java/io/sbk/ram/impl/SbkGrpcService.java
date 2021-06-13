/**
 * Copyright (c) KMG. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.sbk.ram.impl;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.sbk.ram.ConnectionsCount;
import io.sbk.ram.RamParameters;
import io.sbk.grpc.ClientID;
import io.sbk.grpc.Config;
import io.sbk.grpc.LatenciesRecord;
import io.sbk.grpc.ServiceGrpc;
import io.sbk.perl.Time;

import java.security.InvalidKeyException;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SbkGrpcService extends ServiceGrpc.ServiceImplBase {
    private final AtomicLong clientID;
    private final AtomicInteger connections;
    private final Config config;
    private final ConnectionsCount connectionsCount;
    private final Queue<LatenciesRecord> outQueue;
    private final RamParameters params;


    public SbkGrpcService(RamParameters params, Time time, long minLatency, long maxLatency,
                          ConnectionsCount connectionsCount, Queue<LatenciesRecord> outQueue) {
        super();
        clientID = new AtomicLong(0);
        connections = new AtomicInteger(0);
        Config.Builder builder = Config.newBuilder();
        builder.setStorageName(params.getStorageName());
        builder.setActionValue(params.getAction().ordinal());
        builder.setTimeUnitValue(time.getTimeUnit().ordinal());
        builder.setMaxLatency(maxLatency);
        builder.setMinLatency(minLatency);
        config = builder.build();
        this.params = params;
        this.connectionsCount = connectionsCount;
        this.outQueue = outQueue;
    }

    @Override
    public void getConfig(com.google.protobuf.Empty request,
                          io.grpc.stub.StreamObserver<io.sbk.grpc.Config> responseObserver) {
        if (connections.get() < params.getMaxConnections()) {
            responseObserver.onNext(config);
            responseObserver.onCompleted();
        } else {
            Status retError = Status.RESOURCE_EXHAUSTED.withDescription("SBK GRPC Server, Maximum clients Exceeded");
            responseObserver.onError(retError.asRuntimeException());
        }
    }

    @Override
    public void registerClient(io.sbk.grpc.Config request,
                               io.grpc.stub.StreamObserver<io.sbk.grpc.ClientID> responseObserver) {
        responseObserver.onNext(ClientID.newBuilder().setId(clientID.incrementAndGet()).build());
        responseObserver.onCompleted();
        connectionsCount.incrementConnections(1);
        connections.incrementAndGet();
    }


    @Override
    public void addLatenciesRecord(io.sbk.grpc.LatenciesRecord request,
                                   io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
        try {
            outQueue.add(request);
            if (responseObserver != null) {
                responseObserver.onNext(Empty.newBuilder().build());
                responseObserver.onCompleted();
            }

        } catch (IllegalStateException ex) {
            ex.printStackTrace();
            if (responseObserver != null) {
                responseObserver.onError(new InvalidKeyException());
            }
        }
    }

    @Override
    public void closeClient(io.sbk.grpc.ClientID request,
                            io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
        connectionsCount.decrementConnections(1);
        connections.decrementAndGet();
        if (responseObserver != null) {
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}