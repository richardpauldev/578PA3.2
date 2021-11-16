package client;

import edu.umass.cs.gigapaxos.interfaces.Request;

import java.io.IOException;
import java.net.InetSocketAddress;

public class GPClient extends Client {

	public GPClient() throws IOException {
		super();
	}

	public final void send(InetSocketAddress isa, Request request) throws
			IOException {

	}

	public static Request makeDBAppRequest(String request) {
		throw new RuntimeException("To be implemented");
	}

	public static void main(String[] args) {
		boolean x = true;
		Request r = null;
		String s = "qwe";
		Object o = (x ? r : s);
	}
}
