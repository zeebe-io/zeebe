/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.protocol.impl.encoding;

import static io.zeebe.protocol.record.BrokerInfoEncoder.clusterSizeNullValue;
import static io.zeebe.protocol.record.BrokerInfoEncoder.nodeIdNullValue;
import static io.zeebe.protocol.record.BrokerInfoEncoder.partitionsCountNullValue;
import static io.zeebe.protocol.record.BrokerInfoEncoder.replicationFactorNullValue;
import static io.zeebe.protocol.record.BrokerInfoEncoder.versionHeaderLength;
import static io.zeebe.util.buffer.BufferUtil.wrapString;

import io.zeebe.protocol.impl.Loggers;
import io.zeebe.protocol.record.BrokerInfoDecoder;
import io.zeebe.protocol.record.BrokerInfoDecoder.AddressesDecoder;
import io.zeebe.protocol.record.BrokerInfoDecoder.PartitionHealthDecoder;
import io.zeebe.protocol.record.BrokerInfoDecoder.PartitionLeaderTermsDecoder;
import io.zeebe.protocol.record.BrokerInfoDecoder.PartitionRolesDecoder;
import io.zeebe.protocol.record.BrokerInfoEncoder;
import io.zeebe.protocol.record.BrokerInfoEncoder.AddressesEncoder;
import io.zeebe.protocol.record.BrokerInfoEncoder.PartitionHealthEncoder;
import io.zeebe.protocol.record.BrokerInfoEncoder.PartitionLeaderTermsEncoder;
import io.zeebe.protocol.record.BrokerInfoEncoder.PartitionRolesEncoder;
import io.zeebe.protocol.record.MessageHeaderDecoder;
import io.zeebe.protocol.record.MessageHeaderEncoder;
import io.zeebe.protocol.record.PartitionHealthStatus;
import io.zeebe.protocol.record.PartitionRole;
import io.zeebe.util.buffer.BufferReader;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.buffer.BufferWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.ObjLongConsumer;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

// TODO: This will be fixed in the https://github.com/zeebe-io/zeebe/issues/5640
@SuppressWarnings({"squid:S1200", "squid:S1448"})
public final class BrokerInfo implements BufferReader, BufferWriter {

  private static final String BROKER_INFO_PROPERTY_NAME = "brokerInfo";
  private static final DirectBuffer COMMAND_API_NAME = wrapString("commandApi");

  private static final Logger LOG = Loggers.PROTOCOL_LOGGER;

  private static final Encoder BASE_64_ENCODER = Base64.getEncoder();
  private static final Decoder BASE_64_DECODER = Base64.getDecoder();
  private static final Charset BASE_64_CHARSET = StandardCharsets.UTF_8;

  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

  private final BrokerInfoEncoder bodyEncoder = new BrokerInfoEncoder();
  private final BrokerInfoDecoder bodyDecoder = new BrokerInfoDecoder();
  private final Map<DirectBuffer, DirectBuffer> addresses = new HashMap<>();
  private final Map<Integer, PartitionRole> partitionRoles = new HashMap<>();
  private final Map<Integer, Long> partitionLeaderTerms = new HashMap<>();
  private final Map<Integer, PartitionHealthStatus> partitionHealthStatuses = new HashMap<>();

  private int nodeId;
  private int partitionsCount;
  private int clusterSize;
  private int replicationFactor;
  private DirectBuffer version = new UnsafeBuffer();

  public BrokerInfo() {
    reset();
  }

  public BrokerInfo(final int nodeId, final String commandApiAddress) {
    reset();
    this.nodeId = nodeId;
    setCommandApiAddress(BufferUtil.wrapString(commandApiAddress));
  }

  public BrokerInfo reset() {
    nodeId = nodeIdNullValue();
    partitionsCount = partitionsCountNullValue();
    clusterSize = clusterSizeNullValue();
    replicationFactor = replicationFactorNullValue();
    addresses.clear();
    version.wrap(0, 0);
    clearPartitions();

    return this;
  }

  public void clearPartitions() {
    partitionRoles.clear();
    partitionLeaderTerms.clear();
    partitionHealthStatuses.clear();
  }

  public int getNodeId() {
    return nodeId;
  }

  public BrokerInfo setNodeId(final int nodeId) {
    this.nodeId = nodeId;
    return this;
  }

  public int getPartitionsCount() {
    return partitionsCount;
  }

  public BrokerInfo setPartitionsCount(final int partitionsCount) {
    this.partitionsCount = partitionsCount;
    return this;
  }

  public int getClusterSize() {
    return clusterSize;
  }

  public BrokerInfo setClusterSize(final int clusterSize) {
    this.clusterSize = clusterSize;
    return this;
  }

  public int getReplicationFactor() {
    return replicationFactor;
  }

  public BrokerInfo setReplicationFactor(final int replicationFactor) {
    this.replicationFactor = replicationFactor;
    return this;
  }

  public String getVersion() {
    return BufferUtil.bufferAsString(version);
  }

  public void setVersion(final String version) {
    this.version = BufferUtil.wrapString(version);
  }

  public void setVersion(final DirectBuffer buffer, final int offset, final int length) {
    version.wrap(buffer, offset, length);
  }

  public Map<DirectBuffer, DirectBuffer> getAddresses() {
    return addresses;
  }

  public BrokerInfo addAddress(final DirectBuffer apiName, final DirectBuffer address) {
    addresses.put(apiName, address);
    return this;
  }

  public String getCommandApiAddress() {
    final DirectBuffer buffer = addresses.get(COMMAND_API_NAME);
    if (buffer != null) {
      return BufferUtil.bufferAsString(buffer);
    } else {
      return null;
    }
  }

  public BrokerInfo setCommandApiAddress(final String address) {
    return setCommandApiAddress(BufferUtil.wrapString(address));
  }

  public BrokerInfo setCommandApiAddress(final DirectBuffer address) {
    return addAddress(COMMAND_API_NAME, address);
  }

  public Map<Integer, PartitionRole> getPartitionRoles() {
    return partitionRoles;
  }

  public Map<Integer, PartitionHealthStatus> getPartitionHealthStatuses() {
    return partitionHealthStatuses;
  }

  public Map<Integer, Long> getPartitionLeaderTerms() {
    return partitionLeaderTerms;
  }

  public BrokerInfo addPartitionRole(final Integer partitionId, final PartitionRole role) {
    partitionRoles.put(partitionId, role);
    return this;
  }

  public BrokerInfo addPartitionHealth(
      final Integer partitionId, final PartitionHealthStatus status) {
    partitionHealthStatuses.put(partitionId, status);
    return this;
  }

  public BrokerInfo setPartitionUnhealthy(final Integer partitionId) {
    addPartitionHealth(partitionId, PartitionHealthStatus.UNHEALTHY);
    return this;
  }

  public BrokerInfo setPartitionHealthy(final Integer partitionId) {
    addPartitionHealth(partitionId, PartitionHealthStatus.HEALTHY);
    return this;
  }

  public BrokerInfo setPartitionDead(final Integer partitionId) {
    addPartitionHealth(partitionId, PartitionHealthStatus.DEAD);
    return this;
  }

  public BrokerInfo setFollowerForPartition(final int partitionId) {
    partitionLeaderTerms.remove(partitionId);
    return addPartitionRole(partitionId, PartitionRole.FOLLOWER);
  }

  public BrokerInfo setLeaderForPartition(final int partitionId, final long term) {
    partitionLeaderTerms.put(partitionId, term);
    return addPartitionRole(partitionId, PartitionRole.LEADER);
  }

  public BrokerInfo setInactiveForPartition(final int partitionId) {
    partitionLeaderTerms.remove(partitionId);
    return addPartitionRole(partitionId, PartitionRole.INACTIVE);
  }

  // TODO: This will be fixed in the https://github.com/zeebe-io/zeebe/issues/5640
  @SuppressWarnings("squid:S138")
  @Override
  public void wrap(final DirectBuffer buffer, int offset, final int length) {
    reset();

    final int frameEnd = offset + length;

    headerDecoder.wrap(buffer, offset);

    offset += headerDecoder.encodedLength();

    bodyDecoder.wrap(buffer, offset, headerDecoder.blockLength(), headerDecoder.version());

    nodeId = bodyDecoder.nodeId();
    partitionsCount = bodyDecoder.partitionsCount();
    clusterSize = bodyDecoder.clusterSize();
    replicationFactor = bodyDecoder.replicationFactor();

    final AddressesDecoder addressesDecoder = bodyDecoder.addresses();
    while (addressesDecoder.hasNext()) {
      addressesDecoder.next();
      final int apiNameLength = addressesDecoder.apiNameLength();
      final byte[] apiNameBytes = new byte[apiNameLength];
      addressesDecoder.getApiName(apiNameBytes, 0, apiNameLength);

      final int addressLength = addressesDecoder.addressLength();
      final byte[] addressBytes = new byte[addressLength];
      addressesDecoder.getAddress(addressBytes, 0, addressLength);

      addAddress(new UnsafeBuffer(apiNameBytes), new UnsafeBuffer(addressBytes));
    }

    final PartitionRolesDecoder partitionRolesDecoder = bodyDecoder.partitionRoles();
    while (partitionRolesDecoder.hasNext()) {
      partitionRolesDecoder.next();
      addPartitionRole(partitionRolesDecoder.partitionId(), partitionRolesDecoder.role());
    }

    final PartitionLeaderTermsDecoder partitionLeaderTermsDecoder =
        bodyDecoder.partitionLeaderTerms();
    while (partitionLeaderTermsDecoder.hasNext()) {
      partitionLeaderTermsDecoder.next();
      partitionLeaderTerms.put(
          partitionLeaderTermsDecoder.partitionId(), partitionLeaderTermsDecoder.term());
    }

    if (bodyDecoder.versionLength() > 0) {
      bodyDecoder.wrapVersion(version);
    } else {
      bodyDecoder.skipVersion();
    }

    final PartitionHealthDecoder partitionHealthDecoder = bodyDecoder.partitionHealth();
    while (partitionHealthDecoder.hasNext()) {
      partitionHealthDecoder.next();
      partitionHealthStatuses.put(
          partitionHealthDecoder.partitionId(), partitionHealthDecoder.healthStatus());
    }

    assert bodyDecoder.limit() == frameEnd
        : "Decoder read only to position "
            + bodyDecoder.limit()
            + " but expected "
            + frameEnd
            + " as final position";
  }

  @Override
  public int getLength() {
    int length =
        headerEncoder.encodedLength()
            + bodyEncoder.sbeBlockLength()
            + AddressesEncoder.sbeHeaderSize()
            + PartitionRolesEncoder.sbeHeaderSize()
            + PartitionLeaderTermsEncoder.sbeHeaderSize()
            + PartitionHealthEncoder.sbeHeaderSize()
            + versionHeaderLength()
            + version.capacity();

    for (final Entry<DirectBuffer, DirectBuffer> entry : addresses.entrySet()) {
      length +=
          AddressesEncoder.sbeBlockLength()
              + AddressesEncoder.apiNameHeaderLength()
              + entry.getKey().capacity()
              + AddressesEncoder.addressHeaderLength()
              + entry.getValue().capacity();
    }

    length += partitionRoles.size() * PartitionRolesEncoder.sbeBlockLength();
    length += partitionLeaderTerms.size() * PartitionLeaderTermsEncoder.sbeBlockLength();
    length += partitionHealthStatuses.size() * PartitionHealthEncoder.sbeBlockLength();

    return length;
  }

  // TODO: This will be fixed in the https://github.com/zeebe-io/zeebe/issues/5640
  @SuppressWarnings("squid:S138")
  @Override
  public void write(final MutableDirectBuffer buffer, int offset) {
    headerEncoder
        .wrap(buffer, offset)
        .blockLength(bodyEncoder.sbeBlockLength())
        .templateId(bodyEncoder.sbeTemplateId())
        .schemaId(bodyEncoder.sbeSchemaId())
        .version(bodyEncoder.sbeSchemaVersion());

    offset += headerEncoder.encodedLength();

    bodyEncoder
        .wrap(buffer, offset)
        .nodeId(nodeId)
        .partitionsCount(partitionsCount)
        .clusterSize(clusterSize)
        .replicationFactor(replicationFactor);

    final int addressesCount = addresses.size();
    final AddressesEncoder addressesEncoder = bodyEncoder.addressesCount(addressesCount);
    if (addressesCount > 0) {
      for (final Entry<DirectBuffer, DirectBuffer> entry : addresses.entrySet()) {
        final DirectBuffer apiName = entry.getKey();
        final DirectBuffer address = entry.getValue();

        addressesEncoder
            .next()
            .putApiName(apiName, 0, apiName.capacity())
            .putAddress(address, 0, address.capacity());
      }
    }

    final int partitionRolesCount = partitionRoles.size();
    final PartitionRolesEncoder partitionRolesEncoder =
        bodyEncoder.partitionRolesCount(partitionRolesCount);

    if (partitionRolesCount > 0) {
      for (final Entry<Integer, PartitionRole> entry : partitionRoles.entrySet()) {
        partitionRolesEncoder.next().partitionId(entry.getKey()).role(entry.getValue());
      }
    }

    final int partitionLeaderTermsCount = partitionLeaderTerms.size();
    final PartitionLeaderTermsEncoder partitionLeaderTermsEncoder =
        bodyEncoder.partitionLeaderTermsCount(partitionLeaderTermsCount);

    if (partitionLeaderTermsCount > 0) {
      for (final Entry<Integer, Long> entry : partitionLeaderTerms.entrySet()) {
        partitionLeaderTermsEncoder.next().partitionId(entry.getKey()).term(entry.getValue());
      }
    }

    bodyEncoder.putVersion(version, 0, version.capacity());

    final int partitionHealthCount = partitionHealthStatuses.size();
    final PartitionHealthEncoder partitionHealthEncoder =
        bodyEncoder.partitionHealthCount(partitionHealthCount);

    if (partitionHealthCount > 0) {
      for (final Entry<Integer, PartitionHealthStatus> entry : partitionHealthStatuses.entrySet()) {
        partitionHealthEncoder.next().partitionId(entry.getKey()).healthStatus(entry.getValue());
      }
    }
  }

  public static BrokerInfo fromProperties(final Properties properties) {
    final String property = properties.getProperty(BROKER_INFO_PROPERTY_NAME);
    if (property != null) {
      return readFromString(property);
    } else {
      return null;
    }
  }

  private static BrokerInfo readFromString(final String property) {
    final byte[] bytes = BASE_64_DECODER.decode(property.getBytes(BASE_64_CHARSET));

    final BrokerInfo brokerInfo = new BrokerInfo();
    brokerInfo.wrap(new UnsafeBuffer(bytes), 0, bytes.length);

    return brokerInfo;
  }

  public void writeIntoProperties(final Properties memberProperties) {
    memberProperties.setProperty(BROKER_INFO_PROPERTY_NAME, writeToString());
  }

  private String writeToString() {
    final byte[] bytes = new byte[getLength()];
    final UnsafeBuffer buffer = new UnsafeBuffer(bytes);
    write(buffer, 0);
    return new String(BASE_64_ENCODER.encode(bytes), BASE_64_CHARSET);
  }

  public void consumePartitions(
      final ObjLongConsumer<Integer> leaderPartitionConsumer,
      final IntConsumer followerPartitionsConsumer,
      final IntConsumer inactivePartitionsConsumer) {
    consumePartitions(
        p -> {}, leaderPartitionConsumer, followerPartitionsConsumer, inactivePartitionsConsumer);
  }

  public void consumePartitions(
      final IntConsumer partitionConsumer,
      final ObjLongConsumer<Integer> leaderPartitionConsumer,
      final IntConsumer followerPartitionsConsumer,
      final IntConsumer inactivePartitionsConsumer) {
    partitionRoles.forEach(
        (partition, role) -> {
          partitionConsumer.accept(partition);
          switch (role) {
            case LEADER:
              leaderPartitionConsumer.accept(partition, partitionLeaderTerms.get(partition));
              break;
            case FOLLOWER:
              followerPartitionsConsumer.accept(partition);
              break;
            case INACTIVE:
              inactivePartitionsConsumer.accept(partition);
              break;
            default:
              LOG.warn("Failed to decode broker info, found unknown partition role: {}", role);
          }
        });
  }

  public BrokerInfo consumePartitionsHealth(
      final BiConsumer<Integer, PartitionHealthStatus> partitionConsumer) {
    partitionHealthStatuses.forEach(partitionConsumer);
    return this;
  }

  @Override
  public String toString() {
    return "BrokerInfo{"
        + "nodeId="
        + nodeId
        + ", partitionsCount="
        + partitionsCount
        + ", clusterSize="
        + clusterSize
        + ", replicationFactor="
        + replicationFactor
        + ", partitionRoles="
        + partitionRoles
        + ", partitionLeaderTerms="
        + partitionLeaderTerms
        + ", partitionHealthStatuses="
        + partitionHealthStatuses
        + ", version="
        + BufferUtil.bufferAsString(version)
        + '}';
  }
}
