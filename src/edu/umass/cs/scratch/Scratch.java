package edu.umass.cs.scratch;

import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;

public class Scratch {

	private static Integer[] getRandomPortsWithDefault(int defaultPort, int
			numPorts) {
		Integer[] ports = new Integer[numPorts];
		ports[0] = defaultPort;
		HashSet<Integer> portSet = new HashSet<Integer>();
		for (int i = 1; i < numPorts; i++)
			portSet.add(getRandomPort());

		int i = 1;
		for (int port : portSet)
			ports[i++] = port;
		return ports;
	}

	private static int getRandomPort() {
		return Math.max(1024, (int) (Math.random() * 65535));
	}

	private static enum Protocol {
		GET("GET "), FILENAME("Redsox.jpg"), BODY_LENGTH("BODY_BYTE_LENGTH"),
		NEWLINE("\n"), GETHDR("GETHDR "), IAM("IAM "), ID("12345");

		final String val;

		Protocol(String val) {
			this.val = val;
		}
	}

	private static final int GRADING_PORT = 20001;
	private static String fileServer = "plum.cs.umass.edu";
	private static int filePort = 18765;

	public static void main1(String[] args) throws IOException {
		if (args.length >= 3) {
			fileServer = args[1];
			try {
				filePort = Integer.valueOf(args[2]);
			} catch (NumberFormatException nfe) {
				System.err.println("Arguments if supplied must be " + "[mode "
						+ "IP port]");
				throw nfe;
			}
		}
		try {
			InputStream inputStream;
			byte[] header = new byte[256];
			int count = 0;

			// initiate grading connection and send IAM command
			Socket gradingSock = new Socket(fileServer, GRADING_PORT);
			new DataOutputStream(gradingSock.getOutputStream()).writeBytes
					(Protocol.IAM.val + Protocol.ID.val + Protocol.NEWLINE
							.val);

			/* The void-returning writeBytes method below will write the string
			entirely as per its documentation. Using the write method may
			write any number of bytes between 1 and the length of the byte
			array argument specified.*/

			// establish TCP connection to file server
			Socket clientSock = new Socket(fileServer, filePort);
			new DataOutputStream(clientSock.getOutputStream()).writeBytes
					(Protocol.GET.val + Protocol.ID.val + "_" + Protocol
							.FILENAME.val + Protocol.NEWLINE.val);

			// fetch header until two consecutive newlines
//			for (inputStream = clientSock.getInputStream(); !(count > 1 &&
//					header[count - 1] == '\n' && header[count - 2] == '\n'); )
//				header[count++] = (byte) inputStream.read();

			BufferedReader bufferedReader = new BufferedReader(new
					InputStreamReader(inputStream = clientSock.getInputStream
					()));
			String response = bufferedReader.readLine() + bufferedReader
					.readLine() + bufferedReader.readLine() + bufferedReader
					.readLine();

			int bodyLength = Integer.valueOf(response.replace("\n", "")
					.replaceAll("" + ".*" + Protocol.BODY_LENGTH.val +
							":\\s*", "").trim());
			System.out.println(bodyLength);

			// parse header for bodyLength and allocate buffer
			byte[] body = new byte[bodyLength];

			// fetch body counting up to bodyLength
			for (count = 0; count < body.length; ) {
				count += inputStream.read(body, count, body.length - count);
				if(count > 780000) System.out.println(count);
			}

			// compute and submit checksum; no need to read response yet
			byte[] checksum = getXORChecksum(body);
			for (int i = 0; i < checksum.length + 1; i++)
				gradingSock.getOutputStream().write(i < checksum.length ?
						checksum[i] : '\n');

			// write to file
			new FileOutputStream(new File(Protocol.FILENAME.val)).write(body);
			// should close file stream but little possibility of resource leak
			//////////////////  PA1 done here ////////////////////

			// manually verify that you are completing the challenge
			BufferedReader gradingBufferedReader = new BufferedReader(new
					InputStreamReader(gradingSock.getInputStream()));
			System.out.println(gradingBufferedReader.readLine() + "\n" +
					gradingBufferedReader.readLine());


		} catch (IOException ioe) {
			System.err.println(fileServer + ":" + filePort + " or " +
					fileServer + ":" + GRADING_PORT + "likely unreachable");
			throw ioe;
		}
	}

	public static byte[] getXORChecksum(byte[] bytes) {
		byte[] checksum = new byte[4];
		//System.out.println(checksum.length)
		for (int i = 0; i < checksum.length; i++) {
			checksum[i] = bytes[i];
			for (int j = checksum.length + i; j < bytes.length; j += checksum
					.length)
				checksum[i] = (byte) (checksum[i] ^ bytes[j]);
		}
		return checksum;
	}

	public static void main(String[] args) throws IOException {
		String request = "GET redsox.jpg:";
		System.out.println(request.split(":")[1]);

	}

	}
