package io.camunda.zeebe.logstreams.storage.atomix;

import io.camunda.zeebe.logstreams.storage.LogStorage;
import java.nio.ByteBuffer;

public class AtomixReadonlyLogStorage implements LogStorage {

  private final AtomixReaderFactory readerFactory;

  private AtomixReadonlyLogStorage(final AtomixReaderFactory readerFactory) {
    this.readerFactory = readerFactory;
  }

  public static AtomixReadonlyLogStorage ofPartition(final AtomixReaderFactory readerFactory) {
    return new AtomixReadonlyLogStorage(readerFactory);
  }

  @Override
  public AtomixLogStorageReader newReader() {
    return new AtomixLogStorageReader(readerFactory.create());
  }

  @Override
  public void append(
      final long lowestPosition,
      final long highestPosition,
      final ByteBuffer buffer,
      final AppendListener listener) {
    throw new UnsupportedOperationException("The log storage is read-only, go away.");
  }
}
