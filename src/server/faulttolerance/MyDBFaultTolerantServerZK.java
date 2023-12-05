package server.faulttolerance;

import edu.umass.cs.nio.AbstractBytePacketDemultiplexer;
import edu.umass.cs.nio.MessageNIOTransport;
import edu.umass.cs.nio.interfaces.NodeConfig;
import edu.umass.cs.nio.nioutils.NIOHeader;
import edu.umass.cs.nio.nioutils.NodeConfigUtils;
import edu.umass.cs.utils.Util;
import server.ReplicatedServer;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class should implement your replicated fault-tolerant database server if
 * you wish to use Zookeeper or other custom consensus protocols to order client
 * requests.
 * <p>
 * Refer to {@link server.ReplicatedServer} for a starting point for how to do
 * server-server messaging or to {@link server.AVDBReplicatedServer} for a
 * non-fault-tolerant replicated server.
 * <p>
 * You can assume that a single *fault-tolerant* Zookeeper server at the default
 * host:port of localhost:2181 and you can use this service as you please in the
 * implementation of this class.
 * <p>
 * Make sure that both a single instance of Cassandra and a single Zookeeper
 * server are running on their default ports before testing.
 * <p>
 * You can not store in-memory information about request logs for more than
 * {@link #MAX_LOG_SIZE} requests.
 */
public class MyDBFaultTolerantServerZK extends server.MyDBSingleServer {

	/**
	 * Set this value to as small a value with which you can get tests to still
	 * pass. The lower it is, the faster your implementation is. Grader* will
	 * use this value provided it is no greater than its MAX_SLEEP limit.
	 */
	public static final int SLEEP = 1000;

	/**
	 * Set this to true if you want all tables drpped at the end of each run
	 * of tests by GraderFaultTolerance.
	 */
	public static final boolean DROP_TABLES_AFTER_TESTS = true;

	/**
	 * Maximum permitted size of any collection that is used to maintain
	 * request-specific state, i.e., you can not maintain state for more than
	 * MAX_LOG_SIZE requests (in memory or on disk). This constraint exists to
	 * ensure that your logs don't grow unbounded, which forces
	 * checkpointing to
	 * be implemented.
	 */
	public static final int MAX_LOG_SIZE = 400;

	public static final int DEFAULT_PORT = 2181;

	private ZooKeeper zooKeeper;
	public static final String ZOOKEEPER_HOST = "localhost:2181";

	final private Session session;
	final private Cluster cluster;

	protected final String myID;
	protected final MessageNIOTransport<String, String> serverMessenger;

	protected String leader;

	// the sequencer to track the most recent request in the queue
	private static long reqnum = 0;

	private final ReentrantLock lock = new ReentrantLock();

	synchronized static Long incrReqNum() {
		return reqnum++;
	}

	// the sequencer to track the next request to be sent
	private static long expected = 0;

	synchronized static Long incrExpected() {
		return expected++;
	}

	public static final String REQUESTS_PARENT_NODE = "/clientRequests";

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

	private Logger mylog = Logger.getLogger("MyLogger");

	protected static enum Type {
		REQUEST,
		PROPOSAL,
		ACKNOWLEDEMENT;
	}

	/**
	 * @param nodeConfig Server name/address configuration information read
	 *                   from
	 *                   conf/servers.properties.
	 * @param myID       The name of the keyspace to connect to, also the name
	 *                   of the server itself. You can not connect to any other
	 *                   keyspace if using Zookeeper.
	 * @param isaDB      The socket address of the backend datastore to which
	 *                   you need to establish a session.
	 * @throws IOException
	 */
	public MyDBFaultTolerantServerZK(NodeConfig<String> nodeConfig, String myID, InetSocketAddress isaDB)
			throws IOException {
		super(new InetSocketAddress(nodeConfig.getNodeAddress(myID),
				nodeConfig.getNodePort(myID) - ReplicatedServer.SERVER_PORT_OFFSET), isaDB, myID);

		mylog.setLevel(Level.INFO);
		ConsoleHandler ch = new ConsoleHandler();
		ch.setLevel(Level.INFO);
		mylog.addHandler(ch);

		session = (cluster = Cluster.builder().addContactPoint("127.0.0.1")
				.build()).connect(myID);
		mylog.log(Level.INFO, "Server {0} added cluster contact point", new Object[] { myID, });
		// leader is elected as the first node in the nodeConfig
		for (String node : nodeConfig.getNodeIDs()) {
			this.leader = node;
			break;
		}

		this.myID = myID;

		this.serverMessenger = new MessageNIOTransport<String, String>(myID, nodeConfig,
				new AbstractBytePacketDemultiplexer() {
					@Override
					public boolean handleMessage(byte[] bytes, NIOHeader nioHeader) {
						handleMessageFromServer(bytes, nioHeader);
						return true;
					}
				}, true);

		mylog.log(Level.INFO, "Server {0} started on {1}",
				new Object[] { this.myID, this.clientMessenger.getListeningSocketAddress() });

		this.zooKeeper = new ZooKeeper(ZOOKEEPER_HOST, 3000, null);
		try {
			if (zooKeeper.exists(REQUESTS_PARENT_NODE, false) == null) {
				zooKeeper.create(REQUESTS_PARENT_NODE, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			}

			// zooKeeper.getChildren(REQUESTS_PARENT_NODE, new ZooKeeperEventHandler());
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
		}
		scheduler.scheduleAtFixedRate(this::handleNewRequests, 0, 10, TimeUnit.MILLISECONDS);
		mylog.log(Level.INFO, "{0} up and operational :)", myID);
	}

	protected void handleMessageFromClient(byte[] bytes, NIOHeader header) {
		mylog.log(Level.INFO, "Server {0} handling client message: {1}",
				new Object[] { myID, new String(bytes) });
		String pathPrefix = REQUESTS_PARENT_NODE + "/request-";
		try {
			String createdPath = zooKeeper.create(pathPrefix, bytes, ZooDefs.Ids.OPEN_ACL_UNSAFE,
					CreateMode.PERSISTENT_SEQUENTIAL);
			mylog.log(Level.INFO, "{0} created request node {1}", createdPath);
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
			return;
		}
	}

	private static String reqNumToStr(int num) {
		return String.format("request-%010d", num);
	}

	private int last_request = -1;

	private void handleNewRequests() {
		try {
			int num_nodes = zooKeeper.getChildren(REQUESTS_PARENT_NODE, false).size();
			if (num_nodes - 1 == last_request)
				return;
			mylog.log(Level.INFO, "{0} handling {1} requests", new Object[] { myID, num_nodes - last_request});
			for (int reqId = last_request + 1; reqId < num_nodes; reqId++) {
				String fullPath = REQUESTS_PARENT_NODE + "/" + reqNumToStr(reqId);
				lock.lock();
				try {
					if (zooKeeper.exists(fullPath, false) != null) {
						byte[] requestData = zooKeeper.getData(fullPath, false, null);
						session.execute(new String(requestData));
						last_request = reqId;
						mylog.log(Level.INFO, "{0} processed {1} and its last request was {2}", new Object[] { myID, fullPath, last_request});
					} else {
						log.log(Level.WARNING, "{0} we didn't find an expected node {1}", new Object[] {myID, fullPath});
					}

				} catch (KeeperException e) {
					e.printStackTrace();
				} finally {
					lock.unlock();
				}
			}
		} catch (KeeperException |
				InterruptedException e) {
			e.printStackTrace();
		}
	}

	protected void handleMessageFromServer(byte[] bytes, NIOHeader header) {
	}

	public void close() {
		super.close();
		this.serverMessenger.stop();
		session.close();
		cluster.close();
		// deleteNodeRecursively(zooKeeper, REQUESTS_PARENT_NODE);
		try {
			zooKeeper.close();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		scheduler.shutdown();
	}

	public static void deleteNodeRecursively(ZooKeeper zooKeeper, String nodePath) { // Helper method for testing 
		try {
			List<String> children = zooKeeper.getChildren(nodePath, false);
			for (String child : children) {
				deleteNodeRecursively(zooKeeper, nodePath + "/" + child);
			}
			zooKeeper.delete(nodePath, -1); 
		} catch (KeeperException.NoNodeException e) {
			// Node already deleted, ignore
		} catch (KeeperException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static enum CheckpointRecovery { // not sure if this checked for, so I left it in. Doesn't do anything
		CHECKPOINT, RESTORE;
	}

	/**
	 * @param args args[0] must be server.properties file and args[1] must be
	 *             myID. The server prefix in the properties file must be
	 *             ReplicatedServer.SERVER_PREFIX. Optional args[2] if
	 *             specified
	 *             will be a socket address for the backend datastore.
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		new MyDBFaultTolerantServerZK(
				NodeConfigUtils.getNodeConfigFromFile(args[0], ReplicatedServer.SERVER_PREFIX,
						ReplicatedServer.SERVER_PORT_OFFSET),
				args[1], args.length > 2 ? Util
						.getInetSocketAddressFromString(args[2]) : new InetSocketAddress("localhost", 9042));
	}

}