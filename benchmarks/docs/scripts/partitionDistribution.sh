#!/usr/bin/env -S java --source 11
package io.camunda.zeebe.broker.it.util;

import static java.lang.System.exit;

import java.util.Arrays;

public class PartitionDistribution {

  private static final String EXPECTED_CALL = "'./partitionDistribution.sh {nodes} {partitionCount} {replicationFactor}";

  public static void main(String[] args) {
    if (args.length != 3)
    {
      final StringBuilder builder = new StringBuilder();

      builder.append("Expected to be called with three arguments, like:\n")
        .append(EXPECTED_CALL)
        .append("\n")
        .append("But got '")
        .append(Arrays.toString(args))
        .append("'.");

      System.out.println(builder.toString());
      exit(-1);
    }

    final int nodeCount = parseArgument("nodeCount", args[0]);
    final int partitionCount = parseArgument("partitionCount", args[1]);
    final int replicationFactor = parseArgument("replicationFactor", args[2]);

    final StringBuilder builder = new StringBuilder("Distribution:\nP\\N");
    for (int idx = 0; idx < nodeCount; idx++) {
      builder.append("|\tN ").append(idx);
    }

    final int[] partitionsPerNodeCounts = new int[nodeCount];
    for (int partitionIdx = 0; partitionIdx < partitionCount; partitionIdx++)
    {

      builder.append("\n").append("P ").append(partitionIdx + 1); // the partition Id is starting with 1

      final String[] nodeRoles = new String[nodeCount];
      final int leaderIdx = partitionIdx % nodeCount;
      for (int idx = 0; idx < replicationFactor; idx++)
      {
        final int currentIdx = (leaderIdx + idx) % nodeCount;
        nodeRoles[currentIdx] = "F  ";
        partitionsPerNodeCounts[currentIdx]++;
      }
      nodeRoles[leaderIdx] = "L  ";

      for (int nodeIdx = 0; nodeIdx < nodeCount; nodeIdx++) {
        builder.append("|\t");
        var value = nodeRoles[nodeIdx];

        if (value == null)
        {
          value = "-  ";
        }
        builder.append(value);
      }
    }

    builder.append("\n\nPartitions per Node:");
    for (int nodeIdx = 0; nodeIdx < nodeCount; nodeIdx++) {
      builder.append("\n")
        .append("N ")
        .append(nodeIdx).append(": ").append(partitionsPerNodeCounts[nodeIdx]);
    }
    System.out.println(builder.toString());
  }

  public static int parseArgument(String argumentName, String value)
  {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException nfe)
    {
      final String format = "Expected an integer for %s, but got %s. Expected call is like this: %s";
      throw new IllegalStateException(String.format(format, argumentName, value, EXPECTED_CALL), nfe);
    }
  }
}
