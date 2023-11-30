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
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.json.JSONException;
import org.json.JSONObject;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;

import client.AVDBClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

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
	private static final String ZOOKEEPER_HOST = "localhost:2181";
	private String currentZnodeName;

	final private Session session;
	final private Cluster cluster;

	protected final String myID;
	protected final MessageNIOTransport<String, String> serverMessenger;

	protected String leader;

	// this is the message queue used to track which messages have not been sent yet
	private ConcurrentHashMap<Long, JSONObject> queue = new ConcurrentHashMap<Long, JSONObject>();
	private CopyOnWriteArrayList<String> notAcked;

	// the sequencer to track the most recent request in the queue
	private static long reqnum = 0;

	synchronized static Long incrReqNum() {
		return reqnum++;
	}

	// the sequencer to track the next request to be sent
	private static long expected = 0;

	synchronized static Long incrExpected() {
		return expected++;
	}

	public class ZooKeeperEventHandler implements Watcher {
		@Override
		public void process(WatchedEvent event) {
			try {
				switch (event.getType()) {
					case NodeCreated:
						onNodeCreated(event.getPath());
						break;
					case NodeDeleted:
						onNodeDeleted(event.getPath());
						break;
					case NodeDataChanged:
						onNodeDataChanged(event.getPath());
						break;
					case NodeChildrenChanged:
						onNodeChildrenChange(event.getPath());
						break;
					default:
						break;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		private void onNodeChildrenChange(String path) {
		}

		private void onNodeDataChanged(String path) {
		}

		private void onNodeDeleted(String path) throws IOException {
			if (path.startsWith(ELECTION_NAMESPACE)) {
				electLeader();
			}
		}

		private void onNodeCreated(String path) {
		}

	}

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

		session = (cluster = Cluster.builder().addContactPoint("127.0.0.1")
				.build()).connect(myID);
		log.log(Level.INFO, "Server {0} added cluster contact point", new Object[] { myID, });
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

		log.log(Level.INFO, "Server {0} started on {1}",
				new Object[] { this.myID, this.clientMessenger.getListeningSocketAddress() });

		this.zooKeeper = new ZooKeeper(ZOOKEEPER_HOST, 3000, new ZooKeeperEventHandler());

		electLeader();
	}

	String ELECTION_NAMESPACE = "/election";

	private void electLeader() throws IOException {
		String electionZnodePath = ELECTION_NAMESPACE + "/" + myID;
		try {
			String znodeFullPath = zooKeeper.create(electionZnodePath, new byte[] {}, ZooDefs.Ids.OPEN_ACL_UNSAFE,
					CreateMode.EPHEMERAL_SEQUENTIAL);

			this.currentZnodeName = znodeFullPath.replace(ELECTION_NAMESPACE + "/", "");

			List<String> childrenNodes = zooKeeper.getChildren(ELECTION_NAMESPACE, false);
			Collections.sort(childrenNodes);
			int index = childrenNodes.indexOf(currentZnodeName);
			if (index == 0) {
				this.leader = myID;
				// Any other leader stuff
			} else {
				String nodeToWatch = childrenNodes.get(index - 1);
				zooKeeper.exists(ELECTION_NAMESPACE + "/" + nodeToWatch, new ZooKeeperEventHandler());
			}
		} catch (KeeperException | InterruptedException e) {
			throw new IOException("Unable to elect leader", e);
		}
	}
	
	protected void handleMessageFromClient(byte[] bytes, NIOHeader header) {
				
		// this is a request sent by callbackSend method
		String request = new String(bytes);
		
		log.log(Level.INFO, "{0} received client message {1} from {2}",
                new Object[]{this.myID, request, header.sndr});
        JSONObject json = null;
        try {
            json = new JSONObject(request);
            request = json.getString(AVDBClient.Keys.REQUEST
                    .toString());
        } catch (JSONException e) {
            //e.printStackTrace();
        }
		
		// forward the request to the leader as a proposal        
		try {
			JSONObject packet = new JSONObject();
			packet.put(AVDBClient.Keys.REQUEST.toString(), request);
			packet.put(AVDBClient.Keys.TYPE.toString(), Type.REQUEST.toString());			
			
			this.serverMessenger.send(leader, packet.toString().getBytes());
			log.log(Level.INFO, "{0} sends a REQUEST {1} to {2}", 
					new Object[]{this.myID, packet, leader});
		} catch (IOException | JSONException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
        
        
        String response = "[success:"+new String(bytes)+"]";       
        if(json!=null){
        	try{
        		json.put(AVDBClient.Keys.RESPONSE.toString(),
                        response);
                response = json.toString();
            } catch (JSONException e) {
                e.printStackTrace();
        	}
        }
        
        try{
	        // when it's done send back response to client
	        serverMessenger.send(header.sndr, response.getBytes());
        } catch (IOException e) {
        	e.printStackTrace();
        }
	}

	protected void handleMessageFromServer(byte[] bytes, NIOHeader header) {
		// deserialize the request
		JSONObject json;
		try {
			json = new JSONObject(new String(bytes));
		} catch (JSONException e) {
			log.log(Level.SEVERE, "JSON parsing error", e);
			return;
		}

		log.log(Level.INFO, "{0} received relayed message {1} from {2}",
				new Object[] { this.myID, json, header.sndr }); // simply log

		// check the type of the request
		try {
			String type = json.getString(AVDBClient.Keys.TYPE.toString());
			if (type.equals(Type.REQUEST.toString())) {
				if (myID.equals(leader)) {

					// put the request into the queue
					Long reqId = incrReqNum();
					json.put(AVDBClient.Keys.REQNUM.toString(), reqId);
					queue.put(reqId, json);
					log.log(Level.INFO, "{0} put request {1} into the queue.",
							new Object[] { this.myID, json });

					if (isReadyToSend(expected)) {
						// retrieve the first request in the queue
						JSONObject proposal = queue.remove(expected);
						if (proposal != null) {
							proposal.put(AVDBClient.Keys.TYPE.toString(), Type.PROPOSAL.toString());
							enqueue();
							broadcastRequest(proposal);
						} else {
							log.log(Level.INFO,
									"{0} is ready to send request {1}, but the message has already been retrieved.",
									new Object[] { this.myID, expected });
						}

					}
				} else {
					log.log(Level.SEVERE, "{0} received REQUEST message from {1} which should not be here.",
							new Object[] { this.myID, header.sndr });
				}
			} else if (type.equals(Type.PROPOSAL.toString())) {

				// execute the query and send back the acknowledgement
				String query = json.getString(AVDBClient.Keys.REQUEST.toString());
				long reqId = json.getLong(AVDBClient.Keys.REQNUM.toString());

				session.execute(query);

				JSONObject response = new JSONObject().put(AVDBClient.Keys.RESPONSE.toString(), this.myID)
						.put(AVDBClient.Keys.REQNUM.toString(), reqId)
						.put(AVDBClient.Keys.TYPE.toString(), Type.ACKNOWLEDEMENT.toString());
				serverMessenger.send(header.sndr, response.toString().getBytes());
			} else if (type.equals(Type.ACKNOWLEDEMENT.toString())) {

				// only the leader needs to handle acknowledgement
				if (myID.equals(leader)) {
					// TODO: leader processes ack here
					String node = json.getString(AVDBClient.Keys.RESPONSE.toString());
					if (dequeue(node)) {
						// if the leader has received all acks, then prepare to send the next request
						expected++;
						if (isReadyToSend(expected)) {
							JSONObject proposal = queue.remove(expected);
							if (proposal != null) {
								proposal.put(AVDBClient.Keys.TYPE.toString(), Type.PROPOSAL.toString());
								enqueue();
								broadcastRequest(proposal);
							} else {
								log.log(Level.INFO,
										"{0} is ready to send request {1}, but the message has already been retrieved.",
										new Object[] { this.myID, expected });
							}
						}
					}
				} else {
					log.log(Level.SEVERE, "{0} received ACKNOWLEDEMENT message from {1} which should not be here.",
							new Object[] { this.myID, header.sndr });
				}
			} else {
				log.log(Level.SEVERE, "{0} received unrecongonized message from {1} which should not be here.",
						new Object[] { this.myID, header.sndr });
			}

		} catch (JSONException | IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	private boolean isReadyToSend(long expectedId) {
		if (queue.size() > 0 && queue.containsKey(expectedId)) {
			return true;
		}
		return false;
	}

	private void broadcastRequest(JSONObject req) {
		for (String node : this.serverMessenger.getNodeConfig().getNodeIDs()) {
			try {
				this.serverMessenger.send(node, req.toString().getBytes());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		log.log(Level.INFO, "The leader has broadcast the request {0}", new Object[] { req });
	}

	private void enqueue() {
		notAcked = new CopyOnWriteArrayList<String>();
		for (String node : this.serverMessenger.getNodeConfig().getNodeIDs()) {
			notAcked.add(node);
		}
	}

	private boolean dequeue(String node) {
		if (!notAcked.remove(node)) {
			log.log(Level.SEVERE, "The leader does not have the key {0} in its notAcked", new Object[] { node });
		}
		if (notAcked.size() == 0)
			return true;
		return false;
	}

	/**
	 * TODO: ZooKeeper changes
	 */
	public void close() {
		super.close();
		this.serverMessenger.stop();
		session.close();
		cluster.close();
	}

	public static enum CheckpointRecovery {
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