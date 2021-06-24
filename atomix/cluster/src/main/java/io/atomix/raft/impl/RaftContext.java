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
package io.atomix.raft.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.atomix.utils.concurrent.Threads.namedThreads;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.MemberId;
import io.atomix.raft.ElectionTimer;
import io.atomix.raft.RaftCommitListener;
import io.atomix.raft.RaftException;
import io.atomix.raft.RaftRoleChangeListener;
import io.atomix.raft.RaftServer;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.RaftThreadContextFactory;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.cluster.impl.DefaultRaftMember;
import io.atomix.raft.cluster.impl.RaftClusterContext;
import io.atomix.raft.impl.zeebe.LogCompactor;
import io.atomix.raft.metrics.RaftReplicationMetrics;
import io.atomix.raft.metrics.RaftRoleMetrics;
import io.atomix.raft.protocol.RaftResponse;
import io.atomix.raft.protocol.RaftServerProtocol;
import io.atomix.raft.protocol.TransferRequest;
import io.atomix.raft.roles.ActiveRole;
import io.atomix.raft.roles.CandidateRole;
import io.atomix.raft.roles.FollowerRole;
import io.atomix.raft.roles.InactiveRole;
import io.atomix.raft.roles.LeaderRole;
import io.atomix.raft.roles.PassiveRole;
import io.atomix.raft.roles.PromotableRole;
import io.atomix.raft.roles.RaftRole;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.StorageException;
import io.atomix.raft.storage.log.IndexedRaftLogEntry;
import io.atomix.raft.storage.log.RaftLog;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.system.MetaStore;
import io.atomix.raft.zeebe.EntryValidator;
import io.atomix.utils.concurrent.ComposableFuture;
import io.atomix.utils.concurrent.ThreadContext;
import io.atomix.utils.logging.ContextualLoggerFactory;
import io.atomix.utils.logging.LoggerContext;
import io.camunda.zeebe.snapshots.PersistedSnapshot;
import io.camunda.zeebe.snapshots.ReceivableSnapshotStore;
import io.camunda.zeebe.util.exception.UnrecoverableException;
import io.camunda.zeebe.util.health.FailureListener;
import io.camunda.zeebe.util.health.HealthMonitorable;
import io.camunda.zeebe.util.health.HealthStatus;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Manages the volatile state and state transitions of a Raft server.
 *
 * <p>This class is the primary vehicle for managing the state of a server. All state that is shared
 * across roles (i.e. follower, candidate, leader) is stored in the cluster state. This includes
 * Raft-specific state like the current leader and term, the log, and the cluster configuration.
 */
public class RaftContext implements AutoCloseable, HealthMonitorable {

  protected final String name;
  protected final ThreadContext threadContext;
  protected final ClusterMembershipService membershipService;
  protected final RaftClusterContext cluster;
  protected final RaftServerProtocol protocol;
  protected final RaftStorage storage;
  private final Logger log;
  private final Set<RaftRoleChangeListener> roleChangeListeners = new CopyOnWriteArraySet<>();
  private final Set<Consumer<State>> stateChangeListeners = new CopyOnWriteArraySet<>();
  private final Set<Consumer<RaftMember>> electionListeners = new CopyOnWriteArraySet<>();
  private final Set<RaftCommitListener> commitListeners = new CopyOnWriteArraySet<>();
  private final Set<FailureListener> failureListeners = new CopyOnWriteArraySet<>();
  private final RaftRoleMetrics raftRoleMetrics;
  private final RaftReplicationMetrics replicationMetrics;
  private final MetaStore meta;
  private final RaftLog raftLog;
  private final RaftLogReader logReader;
  private final ReceivableSnapshotStore persistedSnapshotStore;
  private final LogCompactor logCompactor;
  private volatile State state = State.ACTIVE;
  private RaftRole role = new InactiveRole(this);
  private Duration electionTimeout = Duration.ofMillis(500);
  private Duration heartbeatInterval = Duration.ofMillis(150);
  private volatile MemberId leader;
  private volatile long term;
  private MemberId lastVotedFor;
  private long commitIndex;
  private volatile long firstCommitIndex;
  private volatile boolean started;
  private EntryValidator entryValidator;
  private final int maxAppendBatchSize;
  private final int maxAppendsPerFollower;
  // Used for randomizing election timeout
  private final Random random;
  private PersistedSnapshot currentSnapshot;
  private volatile HealthStatus health = HealthStatus.HEALTHY;
  private final RaftLogReader commitEntryReader;

  public RaftContext(
      final String name,
      final MemberId localMemberId,
      final ClusterMembershipService membershipService,
      final RaftServerProtocol protocol,
      final RaftStorage storage,
      final RaftThreadContextFactory threadContextFactory,
      final int maxAppendBatchSize,
      final int maxAppendsPerFollower,
      final Supplier<Random> randomFactory) {
    this.name = checkNotNull(name, "name cannot be null");
    this.membershipService = checkNotNull(membershipService, "membershipService cannot be null");
    this.protocol = checkNotNull(protocol, "protocol cannot be null");
    this.storage = checkNotNull(storage, "storage cannot be null");
    random = randomFactory.get();
    log =
        ContextualLoggerFactory.getLogger(
            getClass(), LoggerContext.builder(RaftServer.class).addValue(name).build());

    // Lock the storage directory.
    if (!storage.lock(localMemberId.id())) {
      throw new StorageException(
          "Failed to acquire storage lock; ensure each Raft server is configured with a distinct storage directory");
    }

    final String baseThreadName = String.format("raft-server-%s-%s", localMemberId.id(), name);
    threadContext =
        threadContextFactory.createContext(
            namedThreads(baseThreadName, log), this::onUncaughtException);

    // Open the metadata store.
    meta = storage.openMetaStore();

    // Load the current term and last vote from disk.
    term = meta.loadTerm();
    lastVotedFor = meta.loadVote();

    // Construct the core log, reader, writer, and compactor.
    raftLog = storage.openLog();
    logReader = raftLog.openReader(RaftLogReader.Mode.ALL);

    // Open the snapshot store.
    persistedSnapshotStore = storage.getPersistedSnapshotStore();
    persistedSnapshotStore.addSnapshotListener(this::setSnapshot);
    // Update the current snapshot because the listener only notifies when a new snapshot is
    // created.
    persistedSnapshotStore
        .getLatestSnapshot()
        .ifPresent(persistedSnapshot -> currentSnapshot = persistedSnapshot);
    verifySnapshotLogConsistent();

    logCompactor = new LogCompactor(this);

    this.maxAppendBatchSize = maxAppendBatchSize;
    this.maxAppendsPerFollower = maxAppendsPerFollower;
    cluster = new RaftClusterContext(localMemberId, this);

    // Register protocol listeners.
    registerHandlers(protocol);

    raftRoleMetrics = new RaftRoleMetrics(name);
    replicationMetrics = new RaftReplicationMetrics(name);
    replicationMetrics.setAppendIndex(raftLog.getLastIndex());
    started = true;
    commitEntryReader = raftLog.openReader();
  }

  private void verifySnapshotLogConsistent() {
    final long currentSnapshotIndex = getCurrentSnapshotIndex();
    if ((currentSnapshotIndex <= 0L && raftLog.getFirstIndex() != 1)
        || (currentSnapshotIndex > 0L
            && currentSnapshot.getIndex() + 1 < raftLog.getFirstIndex())) {
      // There is no snapshot, but the log has been compacted!
      throw new IllegalStateException(
          String.format(
              "Expected to find a snapshot at index >= log's first index %d, but found snapshot %d. A previous snapshot is most likely corrupted.",
              raftLog.getFirstIndex(), currentSnapshotIndex));
    }
  }

  private void setSnapshot(final PersistedSnapshot persistedSnapshot) {
    threadContext.execute(() -> currentSnapshot = persistedSnapshot);
  }

  private void onUncaughtException(final Throwable error) {
    log.error("An uncaught exception occurred, transition to inactive role", error);
    try {
      // to prevent further operations submitted to the threadcontext to execute
      transition(Role.INACTIVE);
    } catch (final Exception e) {
      log.error("An error occurred when transitioning to inactive, closing the raft context", e);
      close();
    }

    notifyFailureListeners(error);
  }

  private void notifyFailureListeners(final Throwable error) {
    try {
      if (error instanceof UnrecoverableException) {
        health = HealthStatus.DEAD;
        failureListeners.forEach(FailureListener::onUnrecoverableFailure);
      } else {
        health = HealthStatus.UNHEALTHY;
        failureListeners.forEach(FailureListener::onFailure);
      }
    } catch (final Exception e) {
      log.error("Could not notify failure listeners", e);
    }
  }

  /** Registers server handlers on the configured protocol. */
  private void registerHandlers(final RaftServerProtocol protocol) {
    protocol.registerConfigureHandler(request -> runOnContext(() -> role.onConfigure(request)));
    protocol.registerInstallHandler(request -> runOnContext(() -> role.onInstall(request)));
    protocol.registerReconfigureHandler(request -> runOnContext(() -> role.onReconfigure(request)));
    protocol.registerTransferHandler(request -> runOnContext(() -> role.onTransfer(request)));
    protocol.registerAppendHandler(request -> runOnContext(() -> role.onAppend(request)));
    protocol.registerPollHandler(request -> runOnContext(() -> role.onPoll(request)));
    protocol.registerVoteHandler(request -> runOnContext(() -> role.onVote(request)));
  }

  private <R extends RaftResponse> CompletableFuture<R> runOnContext(
      final Supplier<CompletableFuture<R>> function) {
    final CompletableFuture<R> future = new CompletableFuture<>();
    threadContext.execute(
        () ->
            function
                .get()
                .whenComplete(
                    (response, error) -> {
                      if (error == null) {
                        future.complete(response);
                      } else {
                        future.completeExceptionally(error);
                      }
                    }));
    return future;
  }

  public int getMaxAppendBatchSize() {
    return maxAppendBatchSize;
  }

  public int getMaxAppendsPerFollower() {
    return maxAppendsPerFollower;
  }

  /**
   * Adds a role change listener.
   *
   * @param listener The role change listener.
   */
  public void addRoleChangeListener(final RaftRoleChangeListener listener) {
    roleChangeListeners.add(listener);
  }

  /**
   * Removes a role change listener.
   *
   * @param listener The role change listener.
   */
  public void removeRoleChangeListener(final RaftRoleChangeListener listener) {
    roleChangeListeners.remove(listener);
  }

  /**
   * Awaits a state change.
   *
   * @param state the state for which to wait
   * @param listener the listener to call when the next state change occurs
   */
  public void awaitState(final State state, final Consumer<State> listener) {
    if (this.state == state) {
      listener.accept(this.state);
    } else {
      addStateChangeListener(
          new Consumer<>() {
            @Override
            public void accept(final State state) {
              listener.accept(state);
              removeStateChangeListener(this);
            }
          });
    }
  }

  /**
   * Adds a state change listener.
   *
   * @param listener The state change listener.
   */
  public void addStateChangeListener(final Consumer<State> listener) {
    stateChangeListeners.add(listener);
  }

  /**
   * Removes a state change listener.
   *
   * @param listener The state change listener.
   */
  public void removeStateChangeListener(final Consumer<State> listener) {
    stateChangeListeners.remove(listener);
  }

  /**
   * Adds a new commit listener, which will be notified whenever the commit position changes. Note
   * that it will be called on the Raft thread, and as such should not perform any heavy
   * computation.
   *
   * @param commitListener the listener to add
   */
  public void addCommitListener(final RaftCommitListener commitListener) {
    commitListeners.add(commitListener);
  }

  public void notifyCommitListeners(final long lastCommitIndex) {
    commitEntryReader.seek(lastCommitIndex);

    if (!commitEntryReader.hasNext()) {
      throw new IllegalStateException(
          "This should not happen. Searched for committed entry with index: " + lastCommitIndex);
    }

    final var committedEntry = commitEntryReader.next();

    if (committedEntry == null) {
      throw new IllegalStateException(
          "This should not happen. Searched for committed entry with index: " + lastCommitIndex);
    }
    commitListeners.forEach(listener -> listener.onCommit(committedEntry));
  }
  /**
   * Notifies all listeners of the latest entry.
   *
   * @param lastCommitIndex index of the most recently committed entry
   */
  public void notifyCommitListeners(final IndexedRaftLogEntry lastCommitIndex) {
    commitListeners.forEach(listener -> listener.onCommit(lastCommitIndex));
  }

  /**
   * Sets the commit index.
   *
   * @param commitIndex The commit index.
   * @return the previous commit index
   */
  public long setCommitIndex(final long commitIndex) {
    checkArgument(commitIndex >= 0, "commitIndex must be positive");
    final long previousCommitIndex = this.commitIndex;
    if (commitIndex > previousCommitIndex) {
      this.commitIndex = commitIndex;
      raftLog.setCommitIndex(Math.min(commitIndex, raftLog.getLastIndex()));
      if (raftLog.shouldFlushExplicitly() && isLeader()) {
        // leader counts itself in quorum, so in order to commit the leader must persist
        raftLog.flush();
        setLastWrittenIndex(commitIndex);
      }
      final long configurationIndex = cluster.getConfiguration().index();
      if (configurationIndex > previousCommitIndex && configurationIndex <= commitIndex) {
        cluster.commit();
      }
      setFirstCommitIndex(commitIndex);
      // On start up, set the state to READY after the follower has caught up with the leader
      // https://github.com/zeebe-io/zeebe/issues/4877
      if (state == State.ACTIVE && commitIndex >= firstCommitIndex) {
        state = State.READY;
        stateChangeListeners.forEach(l -> l.accept(state));
      }
      replicationMetrics.setCommitIndex(commitIndex);
    }
    return previousCommitIndex;
  }

  /**
   * Compacts the server logs.
   *
   * @return a future to be completed once the logs have been compacted
   */
  public CompletableFuture<Void> compact() {
    final ComposableFuture<Void> future = new ComposableFuture<>();
    threadContext.execute(() -> logCompactor.compact().whenComplete(future));
    return future;
  }

  /** Attempts to become the leader. */
  public CompletableFuture<Void> anoint() {
    if (role.role() == RaftServer.Role.LEADER) {
      return CompletableFuture.completedFuture(null);
    }

    final CompletableFuture<Void> future = new CompletableFuture<>();

    threadContext.execute(
        () -> {
          // Register a leader election listener to wait for the election of this node.
          final Consumer<RaftMember> electionListener =
              new Consumer<>() {
                @Override
                public void accept(final RaftMember member) {
                  if (member.memberId().equals(cluster.getLocalMember().memberId())) {
                    future.complete(null);
                  } else {
                    future.completeExceptionally(
                        new RaftException.ProtocolException("Failed to transfer leadership"));
                  }
                  removeLeaderElectionListener(this);
                }
              };
          addLeaderElectionListener(electionListener);

          // If a leader already exists, request a leadership transfer from it. Otherwise,
          // transition to the candidate
          // state and attempt to get elected.
          final RaftMember member = getCluster().getLocalMember();
          final RaftMember leader = getLeader();
          if (leader != null) {
            protocol
                .transfer(
                    leader.memberId(),
                    TransferRequest.builder().withMember(member.memberId()).build())
                .whenCompleteAsync(
                    (response, error) -> {
                      if (error != null) {
                        future.completeExceptionally(error);
                      } else if (response.status() == RaftResponse.Status.ERROR) {
                        future.completeExceptionally(response.error().createException());
                      } else {
                        transition(RaftServer.Role.CANDIDATE);
                      }
                    },
                    threadContext);
          } else {
            transition(RaftServer.Role.CANDIDATE);
          }
        });
    return future;
  }

  /**
   * Adds a leader election listener.
   *
   * @param listener The leader election listener.
   */
  public void addLeaderElectionListener(final Consumer<RaftMember> listener) {
    electionListeners.add(listener);
  }

  /**
   * Removes a leader election listener.
   *
   * @param listener The leader election listener.
   */
  public void removeLeaderElectionListener(final Consumer<RaftMember> listener) {
    electionListeners.remove(listener);
  }

  /**
   * Returns the cluster state.
   *
   * @return The cluster state.
   */
  public RaftClusterContext getCluster() {
    return cluster;
  }

  /**
   * Returns the state leader.
   *
   * @return The state leader.
   */
  public DefaultRaftMember getLeader() {
    // Store in a local variable to prevent race conditions and/or multiple volatile lookups.
    final MemberId leader = this.leader;
    return leader != null ? cluster.getMember(leader) : null;
  }

  /** Transition handler. */
  public void transition(final RaftServer.Role role) {
    checkThread();
    checkNotNull(role);

    if (role != Role.FOLLOWER && this.role.role() == role) { // we want to allow follower restarts
      return;
    }

    log.info("Transitioning to {}", role);

    // Close the old state.
    try {
      this.role.stop().get();
    } catch (final InterruptedException | ExecutionException e) {
      throw new IllegalStateException("failed to close Raft state", e);
    }

    // Force state transitions to occur synchronously in order to prevent race conditions.
    try {
      this.role = createRole(role);
      this.role.start().get();
    } catch (final InterruptedException | ExecutionException e) {
      throw new IllegalStateException("failed to initialize Raft state", e);
    }

    if (!this.role.role().active() && role.active()) {
      health = HealthStatus.HEALTHY;
      failureListeners.forEach(FailureListener::onRecovered);
    }

    if (this.role.role() == role) {
      if (this.role.role() == Role.LEADER) {
        // It is safe to assume that transition to leader is only complete after the initial entries
        // are committed.
        final LeaderRole leaderRole = (LeaderRole) this.role;
        leaderRole.onInitialEntriesCommitted(
            () -> {
              if (this.role == leaderRole) { // ensure no other role change happened in between
                notifyRoleChangeListeners();
              }
            });
      } else {
        notifyRoleChangeListeners();
      }
    }
  }

  @Override
  public HealthStatus getHealthStatus() {
    return health;
  }

  /** Adds a failure listener which will be invoked when an uncaught exception occurs */
  @Override
  public void addFailureListener(final FailureListener listener) {
    failureListeners.add(listener);
  }

  /** Remove a failure listener */
  @Override
  public void removeFailureListener(final FailureListener listener) {
    failureListeners.remove(listener);
  }

  private void notifyRoleChangeListeners() {
    try {
      roleChangeListeners.forEach(l -> l.onNewRole(role.role(), getTerm()));
    } catch (final Exception exception) {
      log.error("Unexpected error on calling role change listeners.", exception);
    }
  }

  /** Checks that the current thread is the state context thread. */
  public void checkThread() {
    threadContext.checkThread();
  }

  /** Creates an internal state for the given state type. */
  private RaftRole createRole(final RaftServer.Role role) {
    switch (role) {
      case INACTIVE:
        return new InactiveRole(this);
      case PASSIVE:
        return new PassiveRole(this);
      case PROMOTABLE:
        return new PromotableRole(this);
      case FOLLOWER:
        raftRoleMetrics.becomingFollower();
        return new FollowerRole(this, this::createElectionTimer);
      case CANDIDATE:
        raftRoleMetrics.becomingCandidate();
        return new CandidateRole(this);
      case LEADER:
        raftRoleMetrics.becomingLeader();
        return new LeaderRole(this);
      default:
        throw new AssertionError();
    }
  }

  private ElectionTimer createElectionTimer(final Runnable triggerElection, final Logger log) {
    return new RandomizedElectionTimer(
        electionTimeout, threadContext, random, triggerElection, log);
  }

  /** Transitions the server to the base state for the given member type. */
  public void transition(final RaftMember.Type type) {
    switch (type) {
      case ACTIVE:
        if (!(role instanceof ActiveRole)) {
          transition(RaftServer.Role.FOLLOWER);
        }
        break;
      case PROMOTABLE:
        if (role.role() != RaftServer.Role.PROMOTABLE) {
          transition(RaftServer.Role.PROMOTABLE);
        }
        break;
      case PASSIVE:
        if (role.role() != RaftServer.Role.PASSIVE) {
          transition(RaftServer.Role.PASSIVE);
        }
        break;
      default:
        if (role.role() != RaftServer.Role.INACTIVE) {
          transition(RaftServer.Role.INACTIVE);
        }
        break;
    }
  }

  @Override
  public void close() {
    started = false;
    // Unregister protocol listeners.
    unregisterHandlers(protocol);

    logCompactor.close();

    // Close the log.
    try {
      raftLog.close();
    } catch (final Exception e) {
      log.error("Failed to close raft log", e);
    }

    // Close the metastore.
    try {
      meta.close();
    } catch (final Exception e) {
      log.error("Failed to close metastore", e);
    }

    // Close the snapshot store.
    try {
      persistedSnapshotStore.close();
    } catch (final Exception e) {
      log.error("Failed to close snapshot store", e);
    }

    // close thread contexts
    threadContext.close();
  }

  /** Unregisters server handlers on the configured protocol. */
  private void unregisterHandlers(final RaftServerProtocol protocol) {
    protocol.unregisterConfigureHandler();
    protocol.unregisterInstallHandler();
    protocol.unregisterReconfigureHandler();
    protocol.unregisterTransferHandler();
    protocol.unregisterAppendHandler();
    protocol.unregisterPollHandler();
    protocol.unregisterVoteHandler();
  }

  @Override
  public String toString() {
    return getClass().getCanonicalName();
  }

  /**
   * Returns the commit index.
   *
   * @return The commit index.
   */
  public long getCommitIndex() {
    return commitIndex;
  }

  /**
   * Returns the election timeout.
   *
   * @return The election timeout.
   */
  public Duration getElectionTimeout() {
    return electionTimeout;
  }

  /**
   * Sets the election timeout.
   *
   * @param electionTimeout The election timeout.
   */
  public void setElectionTimeout(final Duration electionTimeout) {
    this.electionTimeout = electionTimeout;
  }

  /**
   * Returns the first commit index.
   *
   * @return The first commit index.
   */
  public long getFirstCommitIndex() {
    return firstCommitIndex;
  }

  /**
   * Sets the first commit index.
   *
   * @param firstCommitIndex The first commit index.
   */
  public void setFirstCommitIndex(final long firstCommitIndex) {
    if (this.firstCommitIndex == 0) {
      this.firstCommitIndex = firstCommitIndex;
      log.info(
          "Setting firstCommitIndex to {}. RaftServer is ready only after it has committed events upto this index",
          firstCommitIndex);
    }
  }

  /**
   * Returns the heartbeat interval.
   *
   * @return The heartbeat interval.
   */
  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  /**
   * Sets the heartbeat interval.
   *
   * @param heartbeatInterval The Raft heartbeat interval.
   */
  public void setHeartbeatInterval(final Duration heartbeatInterval) {
    this.heartbeatInterval = checkNotNull(heartbeatInterval, "heartbeatInterval cannot be null");
  }

  /**
   * Returns the entry validator to be called when an entry is appended.
   *
   * @return The entry validator.
   */
  public EntryValidator getEntryValidator() {
    return entryValidator;
  }

  /**
   * Sets the entry validator to be called when an entry is appended.
   *
   * @param validator The entry validator.
   */
  public void setEntryValidator(final EntryValidator validator) {
    entryValidator = validator;
  }

  /**
   * Returns the state last voted for candidate.
   *
   * @return The state last voted for candidate.
   */
  public MemberId getLastVotedFor() {
    return lastVotedFor;
  }

  /**
   * Sets the state last voted for candidate.
   *
   * @param candidate The candidate that was voted for.
   */
  public void setLastVotedFor(final MemberId candidate) {
    // If we've already voted for another candidate in this term then the last voted for candidate
    // cannot be overridden.
    checkState(!(lastVotedFor != null && candidate != null), "Already voted for another candidate");
    final DefaultRaftMember member = cluster.getMember(candidate);
    checkState(member != null, "Unknown candidate: %d", candidate);
    lastVotedFor = candidate;
    meta.storeVote(lastVotedFor);

    if (candidate != null) {
      log.debug("Voted for {}", member.memberId());
    } else {
      log.trace("Reset last voted for");
    }
  }

  /**
   * Returns the server log.
   *
   * @return The server log.
   */
  public RaftLog getLog() {
    return raftLog;
  }

  /**
   * Returns the server log reader.
   *
   * @return The log reader.
   */
  public RaftLogReader getLogReader() {
    return logReader;
  }

  /**
   * Returns the cluster service.
   *
   * @return the cluster service
   */
  public ClusterMembershipService getMembershipService() {
    return membershipService;
  }

  /**
   * Returns the server metadata store.
   *
   * @return The server metadata store.
   */
  public MetaStore getMetaStore() {
    return meta;
  }

  /**
   * Returns the server name.
   *
   * @return The server name.
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the server protocol.
   *
   * @return The server protocol.
   */
  public RaftServerProtocol getProtocol() {
    return protocol;
  }

  /**
   * Returns the current server state.
   *
   * @return The current server state.
   */
  public RaftRole getRaftRole() {
    return role;
  }

  public RaftRoleMetrics getRaftRoleMetrics() {
    return raftRoleMetrics;
  }

  /**
   * Returns the current server role.
   *
   * @return The current server role.
   */
  public RaftServer.Role getRole() {
    return role.role();
  }

  /**
   * Returns the server state machine.
   *
   * @return The log compactor.
   */
  public LogCompactor getLogCompactor() {
    return logCompactor;
  }

  /**
   * Returns the server snapshot store.
   *
   * @return The server snapshot store.
   */
  public ReceivableSnapshotStore getPersistedSnapshotStore() {
    return persistedSnapshotStore;
  }

  /**
   * Returns the current server state.
   *
   * @return the current server state
   */
  public State getState() {
    return state;
  }

  /**
   * Returns the server storage.
   *
   * @return The server storage.
   */
  public RaftStorage getStorage() {
    return storage;
  }

  /**
   * Returns the state term.
   *
   * @return The state term.
   */
  public long getTerm() {
    return term;
  }

  /**
   * Sets the state term.
   *
   * @param term The state term.
   */
  public void setTerm(final long term) {
    if (term > this.term) {
      this.term = term;
      leader = null;
      lastVotedFor = null;
      meta.storeTerm(this.term);
      meta.storeVote(lastVotedFor);
      log.debug("Set term {}", term);
    }
  }

  public void setLastWrittenIndex(final long index) {
    meta.storeLastWrittenIndex(index);
  }

  /**
   * Returns the execution context.
   *
   * @return The execution context.
   */
  public ThreadContext getThreadContext() {
    return threadContext;
  }

  /**
   * Returns a boolean indicating whether this server is the current leader.
   *
   * @return Indicates whether this server is the leader.
   */
  public boolean isLeader() {
    final MemberId leader = this.leader;
    return leader != null && leader.equals(cluster.getLocalMember().memberId());
  }

  /**
   * Sets the state leader.
   *
   * @param leader The state leader.
   */
  public void setLeader(final MemberId leader) {
    if (!Objects.equals(this.leader, leader)) {
      if (leader == null) {
        this.leader = null;
      } else {
        // If a valid leader ID was specified, it must be a member that's currently a member of the
        // ACTIVE members configuration. Note that we don't throw exceptions for unknown members.
        // It's
        // possible that a failure following a configuration change could result in an unknown
        // leader
        // sending AppendRequest to this server. Simply configure the leader if it's known.
        final DefaultRaftMember member = cluster.getMember(leader);
        if (member != null) {
          this.leader = leader;
          log.info("Found leader {}", member.memberId());
          electionListeners.forEach(l -> l.accept(member));
        }
      }

      log.trace("Set leader {}", this.leader);
    }
  }

  public PersistedSnapshot getCurrentSnapshot() {
    return currentSnapshot;
  }

  public long getCurrentSnapshotIndex() {
    return currentSnapshot != null ? currentSnapshot.getIndex() : 0L;
  }

  public boolean isRunning() {
    return started;
  }

  public RaftReplicationMetrics getReplicationMetrics() {
    return replicationMetrics;
  }

  public Random getRandom() {
    return random;
  }

  /** Raft server state. */
  public enum State {
    ACTIVE,
    READY,
  }
}
