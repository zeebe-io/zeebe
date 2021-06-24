/*
 * Copyright 2015-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.roles;

import com.google.common.base.Throwables;
import io.atomix.raft.RaftError;
import io.atomix.raft.RaftException;
import io.atomix.raft.RaftException.NoLeader;
import io.atomix.raft.RaftServer;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.cluster.impl.DefaultRaftMember;
import io.atomix.raft.cluster.impl.RaftMemberContext;
import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.protocol.AppendRequest;
import io.atomix.raft.protocol.AppendResponse;
import io.atomix.raft.protocol.ConfigureRequest;
import io.atomix.raft.protocol.ConfigureResponse;
import io.atomix.raft.protocol.PollRequest;
import io.atomix.raft.protocol.PollResponse;
import io.atomix.raft.protocol.RaftResponse;
import io.atomix.raft.protocol.ReconfigureRequest;
import io.atomix.raft.protocol.ReconfigureResponse;
import io.atomix.raft.protocol.TransferRequest;
import io.atomix.raft.protocol.TransferResponse;
import io.atomix.raft.protocol.VoteRequest;
import io.atomix.raft.protocol.VoteResponse;
import io.atomix.raft.storage.log.IndexedRaftLogEntry;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.ConfigurationEntry;
import io.atomix.raft.storage.log.entry.InitialEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.raft.storage.system.Configuration;
import io.atomix.raft.zeebe.ValidationResult;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.concurrent.Scheduled;
import io.camunda.zeebe.journal.JournalException;
import io.camunda.zeebe.snapshots.PersistedSnapshotListener;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Leader state. */
public final class LeaderRole extends ActiveRole implements ZeebeLogAppender {

  private static final int MAX_APPEND_ATTEMPTS = 5;
  private final LeaderAppender appender;
  private Scheduled appendTimer;
  private long configuring;
  private CompletableFuture<Void> commitInitialEntriesFuture;
  private ApplicationEntry lastZbEntry = null;

  public LeaderRole(final RaftContext context) {
    super(context);
    appender = new LeaderAppender(this);
  }

  @Override
  public synchronized CompletableFuture<RaftRole> start() {
    // Reset state for the leader.
    takeLeadership();

    // Append initial entries to the log, including an initial no-op entry and the server's
    // configuration.
    appendInitialEntries().join();

    // Commit the initial leader entries.
    commitInitialEntriesFuture = commitInitialEntries();

    lastZbEntry = findLastZeebeEntry();

    return super.start().thenRun(this::startTimers).thenApply(v -> this);
  }

  @Override
  public synchronized CompletableFuture<Void> stop() {
    return super.stop()
        .thenRun(appender::close)
        .thenRun(this::cancelTimers)
        .thenRun(this::stepDown);
  }

  @Override
  protected PersistedSnapshotListener createSnapshotListener() {
    return null;
  }

  @Override
  public RaftServer.Role role() {
    return RaftServer.Role.LEADER;
  }

  @Override
  public CompletableFuture<ReconfigureResponse> onReconfigure(final ReconfigureRequest request) {
    raft.checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    // If the leader index is 0 or is greater than the commitIndex, reject the promote requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (configuring() || initializing()) {
      return CompletableFuture.completedFuture(
          logResponse(ReconfigureResponse.builder().withStatus(RaftResponse.Status.ERROR).build()));
    }

    // If the member is not a known member of the cluster, fail the promotion.
    final DefaultRaftMember existingMember =
        raft.getCluster().getMember(request.member().memberId());
    if (existingMember == null) {
      return CompletableFuture.completedFuture(
          logResponse(
              ReconfigureResponse.builder()
                  .withStatus(RaftResponse.Status.ERROR)
                  .withError(RaftError.Type.UNKNOWN_SESSION)
                  .build()));
    }

    // If the configuration request index is less than the last known configuration index for
    // the leader, fail the request to ensure servers can't reconfigure an old configuration.
    if (request.index() > 0 && request.index() < raft.getCluster().getConfiguration().index()
        || request.term() != raft.getCluster().getConfiguration().term()) {
      return CompletableFuture.completedFuture(
          logResponse(
              ReconfigureResponse.builder()
                  .withStatus(RaftResponse.Status.ERROR)
                  .withError(RaftError.Type.CONFIGURATION_ERROR)
                  .build()));
    }

    // If the member type has not changed, complete the configuration change successfully.
    if (existingMember.getType() == request.member().getType()) {
      final Configuration configuration = raft.getCluster().getConfiguration();
      return CompletableFuture.completedFuture(
          logResponse(
              ReconfigureResponse.builder()
                  .withStatus(RaftResponse.Status.OK)
                  .withIndex(configuration.index())
                  .withTerm(raft.getCluster().getConfiguration().term())
                  .withTime(raft.getCluster().getConfiguration().time())
                  .withMembers(configuration.members())
                  .build()));
    }

    // Update the member type.
    existingMember.update(request.member().getType(), Instant.now());

    final Collection<RaftMember> members = raft.getCluster().getMembers();

    final CompletableFuture<ReconfigureResponse> future = new CompletableFuture<>();
    configure(members)
        .whenComplete(
            (index, error) -> {
              if (error == null) {
                future.complete(
                    logResponse(
                        ReconfigureResponse.builder()
                            .withStatus(RaftResponse.Status.OK)
                            .withIndex(index)
                            .withTerm(raft.getCluster().getConfiguration().term())
                            .withTime(raft.getCluster().getConfiguration().time())
                            .withMembers(members)
                            .build()));
              } else {
                future.complete(
                    logResponse(
                        ReconfigureResponse.builder()
                            .withStatus(RaftResponse.Status.ERROR)
                            .withError(RaftError.Type.PROTOCOL_ERROR)
                            .build()));
              }
            });
    return future;
  }

  private ApplicationEntry findLastZeebeEntry() {
    final RaftLogReader reader = raft.getLogReader();
    reader.seekToAsqn(Long.MAX_VALUE);

    if (reader.hasNext()) {
      final IndexedRaftLogEntry lastEntry = reader.next();
      if (lastEntry != null && lastEntry.isApplicationEntry()) {
        return lastEntry.getApplicationEntry();
      }
    }

    return null;
  }

  /** Cancels the timers. */
  private void cancelTimers() {
    if (appendTimer != null) {
      log.trace("Cancelling append timer");
      appendTimer.cancel();
    }
  }

  /** Ensures the local server is not the leader. */
  private void stepDown() {
    if (raft.getLeader() != null && raft.getLeader().equals(raft.getCluster().getLocalMember())) {
      raft.setLeader(null);
    }
  }

  /** Sets the current node as the cluster leader. */
  private void takeLeadership() {
    raft.setLeader(raft.getCluster().getLocalMember().memberId());
    raft.getCluster().getRemoteMemberStates().forEach(m -> m.resetState(raft.getLog()));
  }

  /** Appends initial entries to the log to take leadership. */
  private CompletableFuture<Void> appendInitialEntries() {
    final long term = raft.getTerm();
    return append(new RaftLogEntry(term, new InitialEntry())).thenApply(index -> null);
  }

  /** Commits a no-op entry to the log, ensuring any entries from a previous term are committed. */
  private CompletableFuture<Void> commitInitialEntries() {
    // The Raft protocol dictates that leaders cannot commit entries from previous terms until
    // at least one entry from their current term has been stored on a majority of servers. Thus,
    // we force entries to be appended up to the leader's no-op entry. The LeaderAppender will
    // ensure
    // that the commitIndex is not increased until the no-op entry (appender.index()) is committed.
    final CompletableFuture<Void> future = new CompletableFuture<>();
    appender
        .appendEntries(appender.getIndex())
        .whenComplete(
            (resultIndex, error) -> {
              raft.checkThread();
              if (isRunning()) {
                if (error == null) {
                  future.complete(null);
                } else {
                  log.info("Failed to commit the initial entry, stepping down");
                  raft.setLeader(null);
                  raft.transition(RaftServer.Role.FOLLOWER);
                }
              }
            });
    return future;
  }

  /** Starts sending AppendEntries requests to all cluster members. */
  private void startTimers() {
    // Set a timer that will be used to periodically synchronize with other nodes
    // in the cluster. This timer acts as a heartbeat to ensure this node remains
    // the leader.
    log.trace("Starting append timer on fix rate of {}", raft.getHeartbeatInterval());
    appendTimer =
        raft.getThreadContext()
            .schedule(Duration.ZERO, raft.getHeartbeatInterval(), this::appendMembers);
  }

  /**
   * Sends AppendEntries requests to members of the cluster that haven't heard from the leader in a
   * while.
   */
  private void appendMembers() {
    raft.checkThread();
    if (isRunning()) {
      appender.appendEntries();
    }
  }

  /**
   * Returns a boolean value indicating whether a configuration is currently being committed.
   *
   * @return Indicates whether a configuration is currently being committed.
   */
  private boolean configuring() {
    return configuring > 0;
  }

  /**
   * Returns a boolean value indicating whether the leader is still being initialized.
   *
   * @return Indicates whether the leader is still being initialized.
   */
  private boolean initializing() {
    // If the leader index is 0 or is greater than the commitIndex, do not allow configuration
    // changes.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    return appender.getIndex() == 0 || raft.getCommitIndex() < appender.getIndex();
  }

  /** Commits the given configuration. */
  protected CompletableFuture<Long> configure(final Collection<RaftMember> members) {
    raft.checkThread();

    final long term = raft.getTerm();

    final ConfigurationEntry configurationEntry =
        new ConfigurationEntry(System.currentTimeMillis(), members);
    return append(new RaftLogEntry(term, configurationEntry))
        .thenCompose(
            entry -> {
              // Store the index of the configuration entry in order to prevent other configurations
              // from
              // being logged and committed concurrently. This is an important safety property of
              // Raft.
              configuring = entry.index();
              raft.getCluster()
                  .configure(
                      new Configuration(
                          entry.index(),
                          entry.term(),
                          configurationEntry.timestamp(),
                          configurationEntry.members()));

              return appender
                  .appendEntries(entry.index())
                  .whenComplete(
                      (commitIndex, commitError) -> {
                        raft.checkThread();
                        configuring = 0;
                      });
            });
  }

  @Override
  public CompletableFuture<ConfigureResponse> onConfigure(final ConfigureRequest request) {
    if (updateTermAndLeader(request.term(), request.leader())) {
      raft.transition(Role.FOLLOWER);
    }
    return super.onConfigure(request);
  }

  @Override
  public CompletableFuture<TransferResponse> onTransfer(final TransferRequest request) {
    logRequest(request);

    final RaftMemberContext member = raft.getCluster().getMemberState(request.member());
    if (member == null) {
      return CompletableFuture.completedFuture(
          logResponse(
              TransferResponse.builder()
                  .withStatus(RaftResponse.Status.ERROR)
                  .withError(RaftError.Type.ILLEGAL_MEMBER_STATE)
                  .build()));
    }

    final CompletableFuture<TransferResponse> future = new CompletableFuture<>();
    appender
        .appendEntries(raft.getLog().getLastIndex())
        .whenComplete(
            (result, error) -> {
              if (isRunning()) {
                if (error == null) {
                  log.info("Transferring leadership to {}", request.member());
                  raft.transition(RaftServer.Role.FOLLOWER);
                  future.complete(
                      logResponse(
                          TransferResponse.builder().withStatus(RaftResponse.Status.OK).build()));
                } else if (error instanceof CompletionException
                    && error.getCause() instanceof RaftException) {
                  future.complete(
                      logResponse(
                          TransferResponse.builder()
                              .withStatus(RaftResponse.Status.ERROR)
                              .withError(
                                  ((RaftException) error.getCause()).getType(), error.getMessage())
                              .build()));
                } else if (error instanceof RaftException) {
                  future.complete(
                      logResponse(
                          TransferResponse.builder()
                              .withStatus(RaftResponse.Status.ERROR)
                              .withError(((RaftException) error).getType(), error.getMessage())
                              .build()));
                } else {
                  future.complete(
                      logResponse(
                          TransferResponse.builder()
                              .withStatus(RaftResponse.Status.ERROR)
                              .withError(RaftError.Type.PROTOCOL_ERROR, error.getMessage())
                              .build()));
                }
              } else {
                future.complete(
                    logResponse(
                        TransferResponse.builder()
                            .withStatus(RaftResponse.Status.ERROR)
                            .withError(RaftError.Type.ILLEGAL_MEMBER_STATE)
                            .build()));
              }
            });
    return future;
  }

  @Override
  public CompletableFuture<AppendResponse> onAppend(final AppendRequest request) {
    raft.checkThread();
    if (updateTermAndLeader(request.term(), request.leader())) {
      final CompletableFuture<AppendResponse> future = super.onAppend(request);
      raft.transition(RaftServer.Role.FOLLOWER);
      return future;
    } else if (request.term() < raft.getTerm()) {
      logRequest(request);
      return CompletableFuture.completedFuture(
          logResponse(
              AppendResponse.builder()
                  .withStatus(RaftResponse.Status.OK)
                  .withTerm(raft.getTerm())
                  .withSucceeded(false)
                  .withLastLogIndex(raft.getLog().getLastIndex())
                  .withLastSnapshotIndex(raft.getCurrentSnapshotIndex())
                  .build()));
    } else {
      raft.setLeader(request.leader());
      raft.transition(RaftServer.Role.FOLLOWER);
      return super.onAppend(request);
    }
  }

  @Override
  public CompletableFuture<PollResponse> onPoll(final PollRequest request) {
    logRequest(request);

    // If a member sends a PollRequest to the leader, that indicates that it likely healed from
    // a network partition and may have had its status set to UNAVAILABLE by the leader. In order
    // to ensure heartbeats are immediately stored to the member, update its status if necessary.
    final RaftMemberContext member = raft.getCluster().getMemberState(request.candidate());
    if (member != null) {
      member.resetFailureCount();
    }

    return CompletableFuture.completedFuture(
        logResponse(
            PollResponse.builder()
                .withStatus(RaftResponse.Status.OK)
                .withTerm(raft.getTerm())
                .withAccepted(false)
                .build()));
  }

  @Override
  public CompletableFuture<VoteResponse> onVote(final VoteRequest request) {
    if (updateTermAndLeader(request.term(), null)) {
      log.info("Received greater term from {}", request.candidate());
      raft.transition(RaftServer.Role.FOLLOWER);
      return super.onVote(request);
    } else {
      logRequest(request);
      return CompletableFuture.completedFuture(
          logResponse(
              VoteResponse.builder()
                  .withStatus(RaftResponse.Status.OK)
                  .withTerm(raft.getTerm())
                  .withVoted(false)
                  .build()));
    }
  }

  /**
   * Appends an entry to the Raft log.
   *
   * @param entry the entry to append
   * @return a completable future to be completed once the entry has been appended
   */
  private CompletableFuture<IndexedRaftLogEntry> append(final RaftLogEntry entry) {
    CompletableFuture<IndexedRaftLogEntry> resultingFuture = null;
    int retries = 0;

    do {
      try {
        resultingFuture = tryToAppend(entry);
      } catch (final JournalException storageException) {

        // storage exception wraps IOException's
        retries++;
        if (retries > MAX_APPEND_ATTEMPTS) {
          // only solution is to step down now
          log.info("Failed to append after {} retries, stepping down", retries, storageException);
          raft.transition(Role.FOLLOWER);
          resultingFuture = Futures.exceptionalFuture(storageException);
        }

        log.error("Error on appending entry {}, retry.", entry, storageException);

      } catch (final Exception e) {
        // on any other exception - we will fail the append attempt
        log.error("Unexpected exception on appending entry {}.", entry, e);
        resultingFuture = Futures.exceptionalFuture(e);
      }
    } while (resultingFuture == null);

    return resultingFuture;
  }

  private CompletableFuture<IndexedRaftLogEntry> tryToAppend(final RaftLogEntry entry) {
    CompletableFuture<IndexedRaftLogEntry> resultingFuture = null;

    try {
      final IndexedRaftLogEntry indexedEntry = raft.getLog().append(entry);
      raft.getReplicationMetrics().setAppendIndex(indexedEntry.index());
      log.trace("Appended {}", indexedEntry);
      resultingFuture = CompletableFuture.completedFuture(indexedEntry);
    } catch (final JournalException.OutOfDiskSpace e) {

      // if this happens then compact will also not help, since we need to create a snapshot
      // before. Furthermore we do snapshot's on regular basis, which mean it had delete data
      // if this were possible
      log.warn("Out of disk space, stepping down", e);

      // only solution is to step down now
      raft.transition(Role.FOLLOWER);
      resultingFuture = Futures.exceptionalFuture(e);
    }

    return resultingFuture;
  }

  @Override
  public void appendEntry(
      final long lowestPosition,
      final long highestPosition,
      final ByteBuffer data,
      final AppendListener appendListener) {
    raft.getThreadContext()
        .execute(() -> safeAppendEntry(lowestPosition, highestPosition, data, appendListener));
  }

  private void safeAppendEntry(
      final long lowestPosition,
      final long highestPosition,
      final ByteBuffer data,
      final AppendListener appendListener) {
    raft.checkThread();

    final ApplicationEntry entry = new ApplicationEntry(lowestPosition, highestPosition, data);

    if (!isRunning()) {
      appendListener.onWriteError(
          new NoLeader("LeaderRole is closed and cannot be used as appender"));
      return;
    }

    final ValidationResult result = raft.getEntryValidator().validateEntry(lastZbEntry, entry);
    if (result.failed()) {
      appendListener.onWriteError(new IllegalStateException(result.getErrorMessage()));
      raft.transition(Role.FOLLOWER);
    } else {
      append(new RaftLogEntry(raft.getTerm(), entry))
          .whenComplete(
              (indexed, error) -> {
                if (error != null) {
                  appendListener.onWriteError(Throwables.getRootCause(error));
                  if (!(error instanceof JournalException)) {
                    // step down. Otherwise the following event can get appended resulting in gaps
                    log.info(
                        "Unexpected error occurred while appending to local log, stepping down");
                    raft.transition(Role.FOLLOWER);
                  }
                } else {
                  if (indexed.isApplicationEntry()) {
                    lastZbEntry = indexed.getApplicationEntry();
                  }

                  appendListener.onWrite(indexed);
                  replicate(indexed, appendListener);
                }
              });
    }
  }

  private void replicate(final IndexedRaftLogEntry indexed, final AppendListener appendListener) {
    raft.checkThread();
    appender
        .appendEntries(indexed.index())
        .whenCompleteAsync(
            (commitIndex, commitError) -> {
              if (!isRunning()) {
                return;
              }

              // have the state machine apply the index which should do nothing but ensures it keeps
              // up to date with the latest entries so it can handle configuration and initial
              // entries properly on fail over
              if (commitError == null) {
                appendListener.onCommit(indexed);
                raft.notifyCommitListeners(indexed);
              } else {
                appendListener.onCommitError(indexed, commitError);
                // replicating the entry will be retried on the next append request
                log.error("Failed to replicate entry: {}", indexed, commitError);
              }
            },
            raft.getThreadContext());
  }

  public synchronized void onInitialEntriesCommitted(final Runnable runnable) {
    commitInitialEntriesFuture.whenComplete(
        (v, error) -> {
          if (error == null) {
            runnable.run();
          }
        });
  }
}
