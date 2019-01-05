package cc.lovezhy.raft.server.util;

import cc.lovezhy.raft.server.RaftStarter;
import cc.lovezhy.raft.server.node.RaftNode;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class NodeUtils {

    public static List<RaftNode> create3RaftNodes() {
        List<RaftNode> raftNodes = Lists.newArrayList();
        Properties properties = new Properties();

        properties.setProperty("cluster.nodes", "3");
        properties.setProperty("local", "localhost:5283:0");
        properties.setProperty("peer", "localhost:5285:1,localhost:5287:2");
        raftNodes.add(new RaftStarter().start(properties));

        properties.setProperty("cluster.nodes", "3");
        properties.setProperty("local", "localhost:5285:1");
        properties.setProperty("peer", "localhost:5283:0,localhost:5287:2");
        raftNodes.add(new RaftStarter().start(properties));

        properties.setProperty("cluster.nodes", "3");
        properties.setProperty("local", "localhost:5287:2");
        properties.setProperty("peer", "localhost:5283:0,localhost:5285:1");
        raftNodes.add(new RaftStarter().start(properties));
        return raftNodes;
    }

    public static List<RaftNode> create5RaftNodes() {
        List<RaftNode> raftNodes = Lists.newArrayList();
        Properties properties = new Properties();

        properties.setProperty("cluster.nodes", "5");
        properties.setProperty("local", "localhost:5283:0");
        properties.setProperty("peer", "localhost:5285:1,localhost:5287:2,localhost:5289:3,localhost:5291:4");
        raftNodes.add(new RaftStarter().start(properties));

        properties.setProperty("cluster.nodes", "5");
        properties.setProperty("local", "localhost:5285:1");
        properties.setProperty("peer", "localhost:5283:0,localhost:5287:2,localhost:5289:3,localhost:5291:4");
        raftNodes.add(new RaftStarter().start(properties));

        properties.setProperty("cluster.nodes", "5");
        properties.setProperty("local", "localhost:5287:2");
        properties.setProperty("peer", "localhost:5285:1,localhost:5283:0,localhost:5289:3,localhost:5291:4");
        raftNodes.add(new RaftStarter().start(properties));


        properties.setProperty("cluster.nodes", "5");
        properties.setProperty("local", "localhost:5289:3");
        properties.setProperty("peer", "localhost:5285:1,localhost:5283:0,localhost:5287:2,localhost:5291:4");
        raftNodes.add(new RaftStarter().start(properties));

        properties.setProperty("cluster.nodes", "5");
        properties.setProperty("local", "localhost:5291:4");
        properties.setProperty("peer", "localhost:5285:1,localhost:5283:0,localhost:5287:2,localhost:5289:3");
        raftNodes.add(new RaftStarter().start(properties));
        return raftNodes;
    }

    public static RaftNode findLeader(List<RaftNode> raftNodes) {
        for (int i = 0; i < 100; i++) {
            for (RaftNode raftNode : raftNodes) {
                if (raftNode.getNodeScheduler().isLeader()) {
                    return raftNode;
                }
            }
            try {
                Thread.sleep(TimeUnit.MILLISECONDS.toMillis(100));
            } catch (InterruptedException e) {
                //ignore
            }
        }
        return null;
    }
}