package edu.umass.cs.reconfiguration;

import org.json.JSONObject;

import edu.umass.cs.nio.interfaces.Messenger;
import edu.umass.cs.reconfiguration.reconfigurationutils.ConsistentReconfigurableNodeConfig;

/**
 * @author arun
 *
 * @param <NodeIDType>
 */
public class TXRRCDB<NodeIDType> extends RepliconfigurableReconfiguratorDB<NodeIDType> {

	/**
	 * @param app
	 * @param myID
	 * @param consistentNodeConfig
	 * @param niot
	 * @param startCleanSlate
	 */
	public TXRRCDB(
			AbstractReconfiguratorDB<NodeIDType> app,
			NodeIDType myID,
			ConsistentReconfigurableNodeConfig<NodeIDType> consistentNodeConfig,
			Messenger<NodeIDType, JSONObject> niot, boolean startCleanSlate) {
		super(app, myID, consistentNodeConfig, niot, startCleanSlate);
		// TODO Auto-generated constructor stub
	}

}
