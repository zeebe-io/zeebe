/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client.impl.response;

import io.netty.util.NetUtil;
import io.zeebe.client.api.response.BrokerInfo;
import io.zeebe.client.api.response.PartitionInfo;
import io.zeebe.gateway.protocol.GatewayOuterClass;
import java.util.ArrayList;
import java.util.List;

public final class BrokerInfoImpl implements BrokerInfo {

  private final int nodeId;
  private final String host;
  private final int port;
  private final String version;
  private final List<PartitionInfo> partitions;

  public BrokerInfoImpl(final GatewayOuterClass.BrokerInfo broker) {
    nodeId = broker.getNodeId();
    host = broker.getHost();
    port = broker.getPort();
    version = broker.getVersion();

    partitions = new ArrayList<>();
    for (final GatewayOuterClass.Partition partition : broker.getPartitionsList()) {
      partitions.add(new PartitionInfoImpl(partition));
    }
  }

  @Override
  public int getNodeId() {
    return nodeId;
  }

  @Override
  public String getHost() {
    return host;
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public String getAddress() {
    return NetUtil.toSocketAddressString(host, port);
  }

  @Override
  public String getVersion() {
    return version;
  }

  @Override
  public List<PartitionInfo> getPartitions() {
    return partitions;
  }

  @Override
  public String toString() {
    return "BrokerInfoImpl{"
        + "nodeId="
        + nodeId
        + ", host='"
        + host
        + '\''
        + ", port="
        + port
        + ", version="
        + version
        + ", partitions="
        + partitions
        + '}';
  }
}
