/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.db;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * The zeebe database, to store key value pairs in different column families. The column families
 * are defined via the specified {@link ColumnFamilyType} enum.
 *
 * <p>To access and store key-value pairs in a specific column family the user needs to create a
 * ColumnFamily instance via {@link #createColumnFamily(Enum, DbContext, DbKey, DbValue)}. If the
 * column family instances are created they are type save, which makes it possible that only the
 * defined key and value types are stored in the column family.
 *
 * @param <ColumnFamilyType>
 */
public interface ZeebeDb<ColumnFamilyType extends Enum<ColumnFamilyType>> extends AutoCloseable {

  /**
   * Creates an instance of a specific column family to access and store key-value pairs in that
   * column family. The key and value instances are used to ensure type safety.
   *
   * <p>If the column family instance is created only the defined key and value types can be stored
   * in the column family.
   *
   * @param <KeyType> the key type of the column family
   * @param <ValueType> the value type of the column family
   * @param columnFamily the enum instance of the column family
   * @param context
   * @param keyInstance this instance defines the type of the column family key type
   * @param valueInstance this instance defines the type of the column family value type
   * @return the created column family instance
   */
  <KeyType extends DbKey, ValueType extends DbValue>
      ColumnFamily<KeyType, ValueType> createColumnFamily(
          ColumnFamilyType columnFamily,
          DbContext context,
          KeyType keyInstance,
          ValueType valueInstance);

  /**
   * Creates a snapshot of the current database in the given directory.
   *
   * @param snapshotDir the directory where the snapshot should be stored
   */
  void createSnapshot(File snapshotDir);

  Optional<String> getProperty(ColumnFamilyType columnFamilyName, String propertyName);

  DbContext createContext();

  /**
   * Checks the database if the given column is empty.
   *
   * @param column the enum of the column to check
   * @param context the context that is used to access the database
   * @return {@code true} if the column is empty, otherwise {@code false}
   */
  boolean isEmpty(ColumnFamilyType column, DbContext context);

  void compactRange();

  void compactFiles(List<String> filesToCompact);
}
