/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.gateway;

import io.atomix.utils.net.Address;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.zeebe.gateway.ResponseMapper.BrokerResponseMapper;
import io.zeebe.gateway.grpc.ServerStreamObserver;
import io.zeebe.gateway.impl.broker.BrokerClient;
import io.zeebe.gateway.impl.broker.RequestRetryHandler;
import io.zeebe.gateway.impl.broker.cluster.BrokerClusterState;
import io.zeebe.gateway.impl.broker.cluster.BrokerTopologyManager;
import io.zeebe.gateway.impl.broker.request.BrokerRequest;
import io.zeebe.gateway.impl.job.ActivateJobsHandler;
import io.zeebe.gateway.protocol.GatewayOuterClass.ActivateJobsRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.ActivateJobsResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.BrokerInfo;
import io.zeebe.gateway.protocol.GatewayOuterClass.BrokerInfo.Builder;
import io.zeebe.gateway.protocol.GatewayOuterClass.CancelWorkflowInstanceRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.CancelWorkflowInstanceResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.CompleteJobRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.CompleteJobResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateWorkflowInstanceRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateWorkflowInstanceResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateWorkflowInstanceWithResultRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateWorkflowInstanceWithResultResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.DeployWorkflowRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.DeployWorkflowResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.FailJobRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.FailJobResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.Partition;
import io.zeebe.gateway.protocol.GatewayOuterClass.Partition.PartitionBrokerRole;
import io.zeebe.gateway.protocol.GatewayOuterClass.PublishMessageRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.PublishMessageResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.ResolveIncidentRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.ResolveIncidentResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.SetVariablesRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.SetVariablesResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.ThrowErrorRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.ThrowErrorResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.TopologyResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.UpdateJobRetriesRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.UpdateJobRetriesResponse;
import io.zeebe.util.VersionUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class EndpointManager {

  private final BrokerClient brokerClient;
  private final BrokerTopologyManager topologyManager;
  private final ActivateJobsHandler activateJobsHandler;
  private final RequestRetryHandler requestRetryHandler;

  public EndpointManager(
      final BrokerClient brokerClient, final ActivateJobsHandler activateJobsHandler) {
    this.brokerClient = brokerClient;
    topologyManager = brokerClient.getTopologyManager();
    this.activateJobsHandler = activateJobsHandler;
    requestRetryHandler = new RequestRetryHandler(brokerClient, topologyManager);
  }

  private void addBrokerInfo(
      final Builder brokerInfo, final Integer brokerId, final BrokerClusterState topology) {
    final String brokerAddress = topology.getBrokerAddress(brokerId);
    final Address address = Address.from(brokerAddress);

    brokerInfo
        .setNodeId(brokerId)
        .setHost(address.host())
        .setPort(address.port())
        .setVersion(topology.getBrokerVersion(brokerId));
  }

  private void addPartitionInfoToBrokerInfo(
      final Builder brokerInfo, final Integer brokerId, final BrokerClusterState topology) {
    topology
        .getPartitions()
        .forEach(
            partitionId -> {
              final Partition.Builder partitionBuilder =
                  Partition.newBuilder().setPartitionId(partitionId);

              if (!setRole(brokerId, partitionId, topology, partitionBuilder)) {
                return;
              }
              brokerInfo.addPartitions(partitionBuilder);
            });
  }

  /**
   * Sets the broker's partition role in the Partition.Builder
   *
   * @return true if it could set the role. False if no role was could be found.
   */
  private boolean setRole(
      final Integer brokerId,
      final Integer partitionId,
      final BrokerClusterState topology,
      final Partition.Builder partitionBuilder) {
    final int partitionLeader = topology.getLeaderForPartition(partitionId);
    final List<Integer> partitionFollowers = topology.getFollowersForPartition(partitionId);
    final List<Integer> partitionInactives = topology.getInactiveNodesForPartition(partitionId);

    if (partitionLeader == brokerId) {
      partitionBuilder.setRole(PartitionBrokerRole.LEADER);
    } else if (partitionFollowers != null && partitionFollowers.contains(brokerId)) {
      partitionBuilder.setRole(PartitionBrokerRole.FOLLOWER);
    } else if (partitionInactives != null && partitionInactives.contains(brokerId)) {
      partitionBuilder.setRole(PartitionBrokerRole.INACTIVE);
    } else {
      return false;
    }

    return true;
  }

  public void activateJobs(
      final ActivateJobsRequest request,
      final ServerStreamObserver<ActivateJobsResponse> responseObserver) {
    activateJobsHandler.activateJobs(request, responseObserver);
  }

  public void cancelWorkflowInstance(
      final CancelWorkflowInstanceRequest request,
      final ServerStreamObserver<CancelWorkflowInstanceResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toCancelWorkflowInstanceRequest,
        ResponseMapper::toCancelWorkflowInstanceResponse,
        responseObserver);
  }

  public void completeJob(
      final CompleteJobRequest request,
      final ServerStreamObserver<CompleteJobResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toCompleteJobRequest,
        ResponseMapper::toCompleteJobResponse,
        responseObserver);
  }

  public void createWorkflowInstance(
      final CreateWorkflowInstanceRequest request,
      final ServerStreamObserver<CreateWorkflowInstanceResponse> responseObserver) {
    sendRequestWithRetryPartitions(
        request,
        RequestMapper::toCreateWorkflowInstanceRequest,
        ResponseMapper::toCreateWorkflowInstanceResponse,
        responseObserver);
  }

  public void createWorkflowInstanceWithResult(
      final CreateWorkflowInstanceWithResultRequest request,
      final ServerStreamObserver<CreateWorkflowInstanceWithResultResponse> responseObserver) {
    if (request.getRequestTimeout() > 0) {
      sendRequestWithRetryPartitions(
          request,
          RequestMapper::toCreateWorkflowInstanceWithResultRequest,
          ResponseMapper::toCreateWorkflowInstanceWithResultResponse,
          responseObserver,
          Duration.ofMillis(request.getRequestTimeout()));
    } else {
      sendRequestWithRetryPartitions(
          request,
          RequestMapper::toCreateWorkflowInstanceWithResultRequest,
          ResponseMapper::toCreateWorkflowInstanceWithResultResponse,
          responseObserver);
    }
  }

  public void deployWorkflow(
      final DeployWorkflowRequest request,
      final ServerStreamObserver<DeployWorkflowResponse> responseObserver) {

    sendRequest(
        request,
        RequestMapper::toDeployWorkflowRequest,
        ResponseMapper::toDeployWorkflowResponse,
        responseObserver);
  }

  public void failJob(
      final FailJobRequest request, final ServerStreamObserver<FailJobResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toFailJobRequest,
        ResponseMapper::toFailJobResponse,
        responseObserver);
  }

  public void throwError(
      final ThrowErrorRequest request,
      final ServerStreamObserver<ThrowErrorResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toThrowErrorRequest,
        ResponseMapper::toThrowErrorResponse,
        responseObserver);
  }

  public void publishMessage(
      final PublishMessageRequest request,
      final ServerStreamObserver<PublishMessageResponse> responseObserver) {

    sendRequest(
        request,
        RequestMapper::toPublishMessageRequest,
        ResponseMapper::toPublishMessageResponse,
        responseObserver);
  }

  public void resolveIncident(
      final ResolveIncidentRequest request,
      final ServerStreamObserver<ResolveIncidentResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toResolveIncidentRequest,
        ResponseMapper::toResolveIncidentResponse,
        responseObserver);
  }

  public void setVariables(
      final SetVariablesRequest request,
      final ServerStreamObserver<SetVariablesResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toSetVariablesRequest,
        ResponseMapper::toSetVariablesResponse,
        responseObserver);
  }

  public void topology(final ServerStreamObserver<TopologyResponse> responseObserver) {
    final TopologyResponse.Builder topologyResponseBuilder = TopologyResponse.newBuilder();
    final BrokerClusterState topology = topologyManager.getTopology();

    if (topology == null) {
      final StatusRuntimeException error =
          Status.UNAVAILABLE.augmentDescription("No brokers available").asRuntimeException();
      responseObserver.onError(error);
      return;
    }

    topologyResponseBuilder
        .setClusterSize(topology.getClusterSize())
        .setPartitionsCount(topology.getPartitionsCount())
        .setReplicationFactor(topology.getReplicationFactor());

    final String gatewayVersion = VersionUtil.getVersion();
    if (gatewayVersion != null && !gatewayVersion.isBlank()) {
      topologyResponseBuilder.setGatewayVersion(gatewayVersion);
    }

    final ArrayList<BrokerInfo> brokers = new ArrayList<>();

    topology
        .getBrokers()
        .forEach(
            brokerId -> {
              final Builder brokerInfo = BrokerInfo.newBuilder();
              addBrokerInfo(brokerInfo, brokerId, topology);
              addPartitionInfoToBrokerInfo(brokerInfo, brokerId, topology);

              brokers.add(brokerInfo.build());
            });

    topologyResponseBuilder.addAllBrokers(brokers);
    final TopologyResponse response = topologyResponseBuilder.build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  public void updateJobRetries(
      final UpdateJobRetriesRequest request,
      final ServerStreamObserver<UpdateJobRetriesResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toUpdateJobRetriesRequest,
        ResponseMapper::toUpdateJobRetriesResponse,
        responseObserver);
  }

  private <GrpcRequestT, BrokerResponseT, GrpcResponseT> void sendRequest(
      final GrpcRequestT grpcRequest,
      final Function<GrpcRequestT, BrokerRequest<BrokerResponseT>> requestMapper,
      final BrokerResponseMapper<BrokerResponseT, GrpcResponseT> responseMapper,
      final ServerStreamObserver<GrpcResponseT> streamObserver) {
    final BrokerRequest<BrokerResponseT> brokerRequest;

    try {
      brokerRequest = requestMapper.apply(grpcRequest);
    } catch (final Exception e) {
      streamObserver.onError(e);
      return;
    }

    brokerClient.sendRequestWithRetry(
        brokerRequest,
        (key, response) -> consumeResponse(responseMapper, streamObserver, key, response),
        streamObserver::onError);
  }

  private <GrpcRequestT, BrokerResponseT, GrpcResponseT> void sendRequestWithRetryPartitions(
      final GrpcRequestT grpcRequest,
      final Function<GrpcRequestT, BrokerRequest<BrokerResponseT>> requestMapper,
      final BrokerResponseMapper<BrokerResponseT, GrpcResponseT> responseMapper,
      final ServerStreamObserver<GrpcResponseT> streamObserver) {
    final BrokerRequest<BrokerResponseT> brokerRequest;

    try {
      brokerRequest = requestMapper.apply(grpcRequest);
    } catch (final Exception e) {
      streamObserver.onError(e);
      return;
    }

    requestRetryHandler.sendRequest(
        brokerRequest,
        (key, response) -> consumeResponse(responseMapper, streamObserver, key, response),
        streamObserver::onError);
  }

  private <GrpcRequestT, BrokerResponseT, GrpcResponseT> void sendRequestWithRetryPartitions(
      final GrpcRequestT grpcRequest,
      final Function<GrpcRequestT, BrokerRequest<BrokerResponseT>> requestMapper,
      final BrokerResponseMapper<BrokerResponseT, GrpcResponseT> responseMapper,
      final ServerStreamObserver<GrpcResponseT> streamObserver,
      final Duration timeout) {
    final BrokerRequest<BrokerResponseT> brokerRequest;

    try {
      brokerRequest = requestMapper.apply(grpcRequest);
    } catch (final Exception e) {
      streamObserver.onError(e);
      return;
    }

    requestRetryHandler.sendRequest(
        brokerRequest,
        (key, response) -> consumeResponse(responseMapper, streamObserver, key, response),
        streamObserver::onError,
        timeout);
  }

  private <BrokerResponseT, GrpcResponseT> void consumeResponse(
      final BrokerResponseMapper<BrokerResponseT, GrpcResponseT> responseMapper,
      final ServerStreamObserver<GrpcResponseT> streamObserver,
      final long key,
      final BrokerResponseT response) {
    final GrpcResponseT grpcResponse = responseMapper.apply(key, response);
    streamObserver.onNext(grpcResponse);
    streamObserver.onCompleted();
  }
}
