package cc.lovezhy.raft.server.node;

import cc.lovezhy.raft.rpc.EndPoint;
import cc.lovezhy.raft.rpc.RpcContext;
import cc.lovezhy.raft.rpc.RpcServer;
import cc.lovezhy.raft.rpc.common.RpcExecutors;
import cc.lovezhy.raft.server.ClusterConfig;
import cc.lovezhy.raft.server.DefaultStateMachine;
import cc.lovezhy.raft.server.log.*;
import cc.lovezhy.raft.server.service.RaftService;
import cc.lovezhy.raft.server.service.RaftServiceImpl;
import cc.lovezhy.raft.server.service.model.*;
import cc.lovezhy.raft.server.storage.StorageType;
import cc.lovezhy.raft.server.utils.Pair;
import cc.lovezhy.raft.server.utils.TimeCountDownUtil;
import cc.lovezhy.raft.server.web.ClientHttpService;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.vertx.core.json.JsonObject;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static cc.lovezhy.raft.server.RaftConstants.*;

public class RaftNode implements RaftService {

    private Logger log;

    private NodeId nodeId;

    /**
     * 节点的当前状态
     */
    private AtomicReference<NodeStatus> currentNodeStatus = new AtomicReference<>();

    /**
     * 集群信息
     */
    private ClusterConfig clusterConfig;

    /**
     * 当前任期
     */
    private volatile Long currentTerm = 0L;

    /**
     * 日志服务
     */
    private LogService logService;

    private List<PeerRaftNode> peerRaftNodes;

    private AtomicLong heartbeatTimeRecorder = new AtomicLong();

    /**
     * 为其他的节点提供Rpc服务
     */
    private EndPoint endPoint;
    private RpcServer rpcServer;

    /**
     * http服务，可以查看节点的状态等信息
     */
    private ClientHttpService httpService;

    /**
     * 提供一些与Node状态相关的原子操作，为选举，超时一些竞态条件服务
     */
    private NodeScheduler nodeScheduler = new NodeScheduler();

    /**
     * PeerNode的一些状态更新，比如MatchIndex等
     */
    private PeerNodeScheduler peerNodeScheduler = new PeerNodeScheduler();

    /**
     * 心跳和超时选举的定时任务的管理
     */
    private TickManager tickManager = new TickManager();

    /**
     * 外界向服务进行Log的请求
     */
    private OuterService outerService = new OuterService();

    public RaftNode(NodeId nodeId, EndPoint endPoint, ClusterConfig clusterConfig, List<PeerRaftNode> peerRaftNodes) {
        Preconditions.checkNotNull(nodeId);
        Preconditions.checkNotNull(endPoint);
        Preconditions.checkNotNull(clusterConfig);
        Preconditions.checkNotNull(peerRaftNodes);
        Preconditions.checkState(peerRaftNodes.size() >= 2, "raft cluster should init with at least 3 server!");

        log = LoggerFactory.getLogger(String.format(getClass().getName() + " node[%d]", nodeId.getPeerId()));

        this.nodeId = nodeId;
        this.peerRaftNodes = peerRaftNodes;
        this.clusterConfig = clusterConfig;
        this.endPoint = endPoint;
        logService = new LogServiceImpl(new DefaultStateMachine(), StorageType.MEMORY);
    }

    public NodeScheduler getNodeScheduler() {
        return nodeScheduler;
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public void init() {
        tickManager.init();
        currentTerm = 0L;
        heartbeatTimeRecorder.set(0L);
        rpcServer = new RpcServer();
        nodeScheduler.setVotedForForce(null);
        RaftService serverService = new RaftServiceImpl(this);
        rpcServer.registerService(serverService);
        rpcServer.start(endPoint);
        httpService = new ClientHttpService(new NodeMonitor(), endPoint.getPort() + 1);
        httpService.createHttpServer();
        peerRaftNodes.forEach(PeerRaftNode::connect);
        nodeScheduler.changeNodeStatus(NodeStatus.FOLLOWER);
        tickManager.tickElectionTimeOut();
    }

    public void reconnect() {
        peerRaftNodes.forEach(PeerRaftNode::connect);
    }


    private void preVote(Long voteTerm) {
        log.debug("start preVote, voteTerm={}", voteTerm);
        long nextElectionTimeOut = tickManager.tickElectionTimeOut();
        if (!nodeScheduler.changeNodeStatusWhenNot(NodeStatus.LEADER, NodeStatus.PRE_CANDIDATE)) {
            return;
        }
        AtomicInteger preVotedGrantedCount = new AtomicInteger(1);
        CountDownLatch latch = new CountDownLatch(clusterConfig.getNodeCount() / 2);
        peerRaftNodes.forEach(peerRaftNode -> {
            try {
                VoteRequest voteRequest = new VoteRequest();
                voteRequest.setTerm(voteTerm);
                voteRequest.setCandidateId(nodeId);
                voteRequest.setLastLogTerm(logService.getLastLogTerm());
                voteRequest.setLastLogIndex(logService.getLastLogIndex());
                peerRaftNode.getRaftService().requestPreVote(voteRequest);
                SettableFuture<VoteResponse> voteResponseFuture = RpcContext.getContextFuture();
                Futures.addCallback(voteResponseFuture, new FutureCallback<VoteResponse>() {
                    @Override
                    public void onSuccess(@Nullable VoteResponse result) {
                        if (Objects.nonNull(result)) {
                            if (result.getVoteGranted()) {
                                preVotedGrantedCount.incrementAndGet();
                            } else if (result.getTerm() > voteTerm) {
                                nodeScheduler.compareAndSetTerm(voteTerm, result.getTerm());
                            }
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        log.error(t.getMessage(), t);
                        latch.countDown();
                    }
                }, RpcExecutors.commonExecutor());
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        });
        try {
            latch.await(nextElectionTimeOut, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
        if (preVotedGrantedCount.get() > clusterConfig.getNodeCount() / 2) {
            voteForLeader(voteTerm);
        }
    }


    private void voteForLeader(Long voteTerm) {
        if (!nodeScheduler.compareAndSetTerm(voteTerm - 1, voteTerm) || !nodeScheduler.compareAndSetVotedFor(null, nodeId)) {
            return;
        }
        log.debug("startElection term={}", currentTerm);
        nodeScheduler.changeNodeStatus(NodeStatus.CANDIDATE);
        long nextWaitTimeOut = tickManager.tickElectionTimeOut();
        //初始值为1，把自己加进去
        AtomicInteger votedCount = new AtomicInteger(1);
        CountDownLatch latch = new CountDownLatch(clusterConfig.getNodeCount() / 2);
        peerRaftNodes.forEach(peerRaftNode -> {
            try {
                VoteRequest voteRequest = new VoteRequest();
                voteRequest.setTerm(currentTerm);
                voteRequest.setCandidateId(nodeId);
                voteRequest.setLastLogIndex(logService.getLastLogIndex());
                voteRequest.setLastLogTerm(logService.getLastLogTerm());
                peerRaftNode.getRaftService().requestVote(voteRequest);
                SettableFuture<VoteResponse> voteResponseFuture = RpcContext.getContextFuture();
                Futures.addCallback(voteResponseFuture, new FutureCallback<VoteResponse>() {
                    @Override
                    public void onSuccess(@Nullable VoteResponse result) {
                        if (Objects.nonNull(result)) {
                            if (result.getVoteGranted()) {
                                log.info("receive vote from={}, term={}", peerRaftNode.getNodeId(), currentTerm);
                                votedCount.incrementAndGet();
                            } else if (result.getTerm() > voteTerm) {
                                nodeScheduler.compareAndSetTerm(voteTerm, result.getTerm());
                            }
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        latch.countDown();
                    }
                }, RpcExecutors.commonExecutor());
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        });
        try {
            latch.await(nextWaitTimeOut, DEFAULT_TIME_UNIT);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
        if (votedCount.get() > clusterConfig.getNodeCount() / 2) {
            log.debug("try to be leader, term={}", voteTerm);
            boolean beLeaderSuccess = nodeScheduler.beLeader(voteTerm);
            if (beLeaderSuccess && nodeScheduler.changeNodeStatusWhenNot(NodeStatus.PRE_CANDIDATE, NodeStatus.LEADER)) {
                log.debug("be the leader sucess, currentTerm={}", voteTerm);
                peerNodeScheduler.updatePeerLogIndex();
                startHeartbeat();
            } else {
                log.debug("be the leader fail, currentTerm={}", voteTerm);
            }
        }
    }

    private void startHeartbeat() {
        peerRaftNodes.forEach(peerRaftNode -> {
            try {
                ReplicatedLogRequest replicatedLogRequest = new ReplicatedLogRequest();
                replicatedLogRequest.setTerm(currentTerm);
                replicatedLogRequest.setLeaderId(nodeId);
                replicatedLogRequest.setLeaderCommit(logService.getLastCommitLogIndex());
                long preLogIndex = peerRaftNode.getNextIndex() - 1;

                replicatedLogRequest.setPrevLogIndex(preLogIndex);
                replicatedLogRequest.setPrevLogTerm(logService.get(preLogIndex).getTerm());

                long currentLastLogIndex = logService.getLastLogIndex();
                replicatedLogRequest.setEntries(logService.get(peerRaftNode.getNextIndex(), logService.getLastLogIndex()));
                peerRaftNode.getRaftService().requestAppendLog(replicatedLogRequest);
                log.debug("send heartbeat to={}", peerRaftNode.getNodeId());
                SettableFuture<ReplicatedLogResponse> responseSettableFuture = RpcContext.getContextFuture();
                Futures.addCallback(responseSettableFuture, new FutureCallback<ReplicatedLogResponse>() {
                    @Override
                    public void onSuccess(@Nullable ReplicatedLogResponse result) {
                        /*
                         * 可能发生
                         * 成为Leader后直接被网络分区了
                         * 然后又好了，此时另外一个分区已经有Leader且Term比自己大
                         */
                        if (result.getTerm() > currentTerm) {
                            log.error("currentTerm={}, remoteServerTerm={}", currentTerm, result.getTerm());
                            log.debug("may have network isolate");
                            //TODO
                        }
                        if (!result.getSuccess()) {
                            if (!peerRaftNode.getNodeStatus().equals(PeerNodeStatus.INSTALLSNAPSHOT)) {
                                long nextPreLogIndex = peerRaftNode.getNextIndex() - 1;
                                //如果已经是在Snapshot中
                                if (logService.hasInSnapshot(nextPreLogIndex)) {
                                    peerRaftNode.setNodeStatus(PeerNodeStatus.INSTALLSNAPSHOT);
                                    Snapshot snapShot = logService.getSnapShot();
                                    InstallSnapshotRequest installSnapShotRequest = new InstallSnapshotRequest();
                                    installSnapShotRequest.setLeaderId(nodeId);
                                    installSnapShotRequest.setTerm(currentTerm);
                                    installSnapShotRequest.setSnapshot(snapShot);
                                    peerRaftNode.getRaftService().requestInstallSnapShot(installSnapShotRequest);
                                    SettableFuture<InstallSnapshotResponse> responseSettableFuture = RpcContext.getContextFuture();
                                    Futures.addCallback(responseSettableFuture, new FutureCallback<InstallSnapshotResponse>() {
                                        @Override
                                        public void onSuccess(@Nullable InstallSnapshotResponse result) {
                                            if (result.getSuccess()) {
                                                peerRaftNode.setNodeStatus(PeerNodeStatus.PROBE);
                                                peerRaftNode.setMatchIndex(snapShot.getLastLogIndex());
                                                peerRaftNode.setNextIndex(snapShot.getLastLogIndex() + 1);
                                            } else {
                                                throw new IllegalStateException("install SnapShot fail");
                                            }
                                        }

                                        @Override
                                        public void onFailure(Throwable t) {
                                            t.printStackTrace();
                                        }
                                    }, RpcExecutors.commonExecutor());
                                } else {
                                    peerRaftNode.setNextIndex(peerRaftNode.getNextIndex() - 1);
                                }
                            }
                        } else {
                            peerRaftNode.setNodeStatus(PeerNodeStatus.NORMAL);
                            peerRaftNode.setNextIndex(currentLastLogIndex + 1);
                            peerRaftNode.setMatchIndex(currentLastLogIndex);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        t.printStackTrace();
                    }
                }, RpcExecutors.commonExecutor());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        tickManager.tickHeartbeat();
    }

    @Override
    public VoteResponse requestPreVote(VoteRequest voteRequest) {
        Long term = currentTerm;
        if (voteRequest.getTerm() > term && logService.isNewerThanSelf(voteRequest.getLastLogTerm(), voteRequest.getLastLogIndex())) {
            return new VoteResponse(term, true);
        } else {
            return new VoteResponse(term, false);
        }
    }

    @Override
    public VoteResponse requestVote(VoteRequest voteRequest) {
        Long term = currentTerm;
        if (term > voteRequest.getTerm()) {
            return new VoteResponse(term, false);
        }
        if (logService.isNewerThanSelf(voteRequest.getLastLogIndex(), voteRequest.getLastLogTerm())) {
            if (nodeScheduler.compareAndSetTerm(term, voteRequest.getTerm()) && (nodeScheduler.compareAndSetVotedFor(null, voteRequest.getCandidateId()) || nodeScheduler.compareAndSetVotedFor(voteRequest.getCandidateId(), voteRequest.getCandidateId()))) {
                nodeScheduler.changeNodeStatus(NodeStatus.FOLLOWER);
                nodeScheduler.receiveHeartbeat();
                tickManager.tickElectionTimeOut();
                return new VoteResponse(voteRequest.getTerm(), true);
            }
        }
        return new VoteResponse(term, false);
    }

    @Override
    public ReplicatedLogResponse requestAppendLog(ReplicatedLogRequest replicatedLogRequest) {
        Long term = currentTerm;

        //感觉应该不会出现，除非是消息延迟
        if (term > replicatedLogRequest.getTerm()) {
            return new ReplicatedLogResponse(term, false);
        }

        Preconditions.checkState(!nodeScheduler.isLeader());

        tickManager.tickElectionTimeOut();

        // normal
        if (Objects.equals(replicatedLogRequest.getLeaderId(), nodeScheduler.getVotedFor())) {
            log.debug("receiveHeartbeat from={}", replicatedLogRequest.getLeaderId());
            nodeScheduler.receiveHeartbeat();
            return appendLog(replicatedLogRequest);
        }

        // 落单的，发现更高Term的Leader，直接变成Follower
        // 三个人选举的情况
        if (nodeScheduler.compareAndSetTerm(term, replicatedLogRequest.getTerm())) {
            nodeScheduler.setVotedForForce(replicatedLogRequest.getLeaderId());
            nodeScheduler.changeNodeStatus(NodeStatus.FOLLOWER);
            nodeScheduler.receiveHeartbeat();
            log.debug("receiveHeartbeat from={}", replicatedLogRequest.getLeaderId());
            return appendLog(replicatedLogRequest);
        }
        return new ReplicatedLogResponse(term, false);
    }

    private ReplicatedLogResponse appendLog(ReplicatedLogRequest replicatedLogRequest) {
        LogEntry logEntry = logService.get(replicatedLogRequest.getPrevLogIndex());
        boolean isSameTerm = false;
        if (Objects.nonNull(logEntry)) {
            isSameTerm = logEntry.getTerm().equals(replicatedLogRequest.getTerm());
        }
        if (isSameTerm) {
            logService.appendLog(replicatedLogRequest.getEntries());
        }
        logService.commit(replicatedLogRequest.getLeaderCommit());
        log.info("/appendLog, isSameTerm={}", isSameTerm);
        return new ReplicatedLogResponse(replicatedLogRequest.getTerm(), isSameTerm);
    }

    @Override
    public InstallSnapshotResponse requestInstallSnapShot(InstallSnapshotRequest installSnapShotRequest) {
        Long term = currentTerm;
        if (installSnapShotRequest.getTerm() < term) {
            return new InstallSnapshotResponse(term, false);
        }
        logService.installSnapshot(installSnapShotRequest.getSnapshot());
        return new InstallSnapshotResponse(term, true);
    }

    public void close() {
        log.info("close");
        this.peerRaftNodes.forEach(PeerRaftNode::close);
        this.nodeScheduler.changeNodeStatus(NodeStatus.FOLLOWER);
        this.rpcServer.close();
        this.httpService.close();
        this.tickManager.cancelAll();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RaftNode raftNode = (RaftNode) o;
        return com.google.common.base.Objects.equal(nodeId, raftNode.nodeId);
    }

    @Override
    public int hashCode() {
        return com.google.common.base.Objects.hashCode(nodeId);
    }

    public class NodeScheduler {

        private AtomicReference<NodeId> votedFor = new AtomicReference<>();

        private ReentrantLock lockScheduler = new ReentrantLock();

        /**
         * timeOut开始选举和收到其他节点信息之间存在竞态条件
         * <p>
         * 同时BeLeader和开始下一任选举之间也存在竞态条件
         * 如果当前节点已经是Leader，那么也不允许修改任期
         *
         * @return 是否更新成功
         * @see NodeScheduler#beLeader(Long)
         */
        boolean compareAndSetTerm(Long expected, Long update) {
            Preconditions.checkNotNull(expected);
            Preconditions.checkNotNull(update);
            if (!Objects.equals(expected, currentTerm) || Objects.equals(currentNodeStatus.get(), NodeStatus.LEADER)) {
                return false;
            }
            try {
                lockScheduler.lock();
                if (!Objects.equals(expected, currentTerm) || Objects.equals(currentNodeStatus.get(), NodeStatus.LEADER)) {
                    return false;
                }
                currentTerm = update;
                if (!Objects.equals(expected, update)) {
                    votedFor.set(null);
                }
                return true;
            } finally {
                lockScheduler.unlock();
            }
        }

        boolean compareAndSetVotedFor(NodeId expect, NodeId update) {
            return votedFor.compareAndSet(expect, update);
        }

        void setVotedForForce(NodeId nodeId) {
            votedFor.set(nodeId);
        }

        /**
         * 修改当前节点的NodeStatus
         *
         * @param update 新节点状态
         */
        boolean changeNodeStatusWhenNot(NodeStatus whenNotStatus, NodeStatus update) {
            Preconditions.checkNotNull(whenNotStatus);
            Preconditions.checkNotNull(update);
            try {
                lockScheduler.lock();
                NodeStatus currentStatus = currentNodeStatus.get();
                if (!currentStatus.equals(whenNotStatus)) {
                    currentNodeStatus.set(update);
                    return true;
                } else {
                    return false;
                }
            } finally {
                lockScheduler.unlock();
            }
        }

        void changeNodeStatus(NodeStatus nodeStatus) {
            Preconditions.checkNotNull(nodeStatus);
            currentNodeStatus.set(nodeStatus);
        }

        /**
         * 判断当前节点是不是Leader
         *
         * @return 是不是Leader
         */
        public boolean isLeader() {
            return Objects.equals(currentNodeStatus.get(), NodeStatus.LEADER);
        }

        /**
         * 因为开始选举之前会开始一个TimeOut
         * 如果TimeOut之后，还未成为Leader，就把任期+1，重新进行选举
         * TimeOut开始和新选举和上一个选举之间存在竞态条件
         * 如果成为Leader的时候，这一轮选举已经超时了，那么还是不能成为Leader
         * 所以在当前选举想要成为Leader，首先得确定当前任期还是选举开始时的任期
         *
         * @param term 成为Leader的任期
         * @return 是否成功成为Leader
         */
        boolean beLeader(Long term) {
            Preconditions.checkNotNull(term);
            if (!Objects.equals(currentTerm, term)) {
                return false;
            }
            try {
                lockScheduler.lock();
                return Objects.equals(currentTerm, term) && currentNodeStatus.get().equals(NodeStatus.CANDIDATE);
            } finally {
                lockScheduler.unlock();
            }
        }

        /**
         * 得到VotedFor
         *
         * @return {nullable} NodeId
         */
        NodeId getVotedFor() {
            return votedFor.get();
        }


        /**
         * 接收到心跳
         */
        void receiveHeartbeat() {
            heartbeatTimeRecorder.set(System.currentTimeMillis());
        }


        /**
         * 判断是否丢失心跳
         */
        boolean isLoseHeartbeat(long timeoutMills) {
            return System.currentTimeMillis() - heartbeatTimeRecorder.get() > timeoutMills;
        }
    }

    public class PeerNodeScheduler {
        /**
         * 成为Leader之后，更新peer节点的NextIndex和MatchIndex
         */
        void updatePeerLogIndex() {
            peerRaftNodes.forEach(peerRaftNode -> {
                peerRaftNode.setNextIndex(logService.getLastLogIndex() + 1);
                peerRaftNode.setMatchIndex(0L);
            });
        }
    }

    /**
     * for ClientHttpService
     */
    public class NodeMonitor {
        public JsonObject getNodeStatus() {
            JsonObject jsonObject = new JsonObject();
            jsonObject.put("NodeStatus", currentNodeStatus.get().toString());
            jsonObject.put("term", currentTerm.toString());
            jsonObject.put("Leader", RaftNode.this.nodeScheduler.getVotedFor().getPeerId());

            Map<NodeId, String> nodeIdUrlMap = Maps.newHashMap();
            RaftNode.this.peerRaftNodes.forEach(peerRaftNode -> {
                EndPoint endPoint = EndPoint.create(peerRaftNode.getEndPoint().getHost(), peerRaftNode.getEndPoint().getPort() + 1);
                nodeIdUrlMap.put(peerRaftNode.getNodeId(), endPoint.toUrl() + "/status");
            });
            jsonObject.put("servers", nodeIdUrlMap);
            return jsonObject;
        }
    }

    public class TickManager {
        private Optional<Future> preVoteFuture = Optional.empty();
        private Optional<Future> heartBeatFuture = Optional.empty();

        void init() {
            preVoteFuture = Optional.empty();
            heartBeatFuture = Optional.empty();
        }

        void cancelAll() {
            preVoteFuture.ifPresent(future -> future.cancel(false));
            heartBeatFuture.ifPresent(future -> future.cancel(false));
        }

        long tickElectionTimeOut() {
            preVoteFuture.ifPresent(future -> future.cancel(false));
            long waitTimeOut = getRandomStartElectionTimeout();
            long voteTerm = currentTerm;
            Future future = TimeCountDownUtil.addSchedulerTask(
                    waitTimeOut,
                    DEFAULT_TIME_UNIT,
                    () -> preVote(voteTerm + 1),
                    () -> nodeScheduler.isLoseHeartbeat(waitTimeOut) && !nodeScheduler.isLeader());
            this.preVoteFuture = Optional.of(future);
            return waitTimeOut;
        }

        void tickHeartbeat() {
            Future future = TimeCountDownUtil.addSchedulerTask(HEART_BEAT_TIME_INTERVAL, DEFAULT_TIME_UNIT, RaftNode.this::startHeartbeat, (Supplier<Boolean>) () -> nodeScheduler.isLeader());
            heartBeatFuture = Optional.of(future);
        }
    }


    public class OuterService {
        public boolean appendLog(DefaultCommand command) {
            return true;
        }
    }

    /**
     * for the web logger
     */
    public class WebLogger {
        private List<Pair<Long, String>> logEntry = Collections.synchronizedList(Lists.newArrayList());

        public void addLog(String item) {
            logEntry.add(Pair.of(System.currentTimeMillis(), item));
        }
    }
}
