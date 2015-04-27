/**
 * 
 */
package JMeter.plugins.functional.samplers.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

/**
 * @author nasangameshwaran
 *
 */
public class CommandLineSocketSampler extends AbstractSampler implements SocketSampler {
	

	/** Default Serial Version ID. */
	private static final long serialVersionUID = 1L;
	
	public static int DEFAULT_CONNECTION_TIMEOUT = 5000; //20000; //20 sec
    public static int DEFAULT_RESPONSE_TIMEOUT = 5000; //20000; //20 sec
    public static int MESSAGE_BACKLOG_COUNT = 3;
    
    private static final Logger log = LoggingManager.getLoggerForClass();
	private String responsePattern;
	private String closeConnectionPattern;
	private Boolean streamingConnection = Boolean.FALSE;
	private String messageBacklog;
	private String contentEncoding;
//	private String requestPayload;
	
	private static final String BASE_URL_FORMAT = "://{0}:{1}/smart-opsboard/";
	private static final String URL_FORMAT = BASE_URL_FORMAT + "wsboard/{2}/{3}";
	
	//0 - location, 1 - Date, 2 - Location Shift Id, 3 - Shift ID
	private static final String ADD_SHIFT_MSG_FORMAT = "'{'\"commandName\":\"AddShift\",\"clientSequence\":0,\"commandContent\":'{'\"serviceLocationId\":\"{0}\",\"locationShiftId\":\"{2}\",\"shiftId\":\"{3}\"'}',\"date\":\"{1}\",\"location\":\"{0}\"'}'";

	public static String appHost = "localhost";
	public static String appPort = "8080";
	public static String location = "BKN01";
	public static String boardDate = "20150501";
	
	
	private static Map<String, ServiceSocket> connectionList;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		CommandLineSocketSampler instance = new CommandLineSocketSampler();
		instance.sample(new Entry());
		
	}

	@Override
	public SampleResult sample(Entry arg0) {
        ServiceSocket socket = null;
        SampleResult sampleResult = new SampleResult();
        sampleResult.setSampleLabel(getName());
        sampleResult.setDataEncoding(getContentEncoding());
        
        //This StringBuilder will track all exceptions related to the protocol processing
        StringBuilder errorList = new StringBuilder();
        errorList.append("\n\n[Problems]\n");
        
        boolean isOK = false;

        //Set the message payload in the Sampler
        String payloadMessage = getRequestPayload();
        sampleResult.setSamplerData(payloadMessage);
        
        //Could improve precission by moving this closer to the action
        sampleResult.sampleStart();

        try {
            socket = getConnectionSocket();
            if (socket == null) {
                //Couldn't open a connection, set the status and exit
                sampleResult.setResponseCode("500");
                sampleResult.setSuccessful(false);
                sampleResult.sampleEnd();
                sampleResult.setResponseMessage(errorList.toString());
                errorList.append(" - Connection couldn't be opened").append("\n");
                return sampleResult;
            }
            
            //Send message only if it is not empty
            if (!payloadMessage.isEmpty()) {
                socket.sendMessage(payloadMessage);
                log.debug("Sending Message: " + payloadMessage);
            }

            int responseTimeout;
            try {
                responseTimeout = Integer.parseInt(getResponseTimeout());
            } catch (NumberFormatException ex) {
                log.warn("Request timeout is not a number; using the default request timeout of " + DEFAULT_RESPONSE_TIMEOUT + "ms");
                responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
            }
            
            //Wait for any of the following:
            // - Response matching response pattern is received
            // - Response matching connection closing pattern is received
            // - Timeout is reached
            socket.awaitClose(responseTimeout, TimeUnit.MILLISECONDS);
            
            //If no response is received set code 204; actually not used...needs to do something else
            if (socket.getResponseMessage() == null || socket.getResponseMessage().isEmpty()) {
                sampleResult.setResponseCode("204");
            }
            
            //Set sampler response code
            if (socket.getError() != 0) {
                isOK = false;
                sampleResult.setResponseCode(socket.getError().toString());
            } else {
                sampleResult.setResponseCodeOK();
                isOK = true;
            }
            
            //set sampler response
            sampleResult.setResponseData(socket.getResponseMessage(), getContentEncoding());
            
        } catch (URISyntaxException e) {
            errorList.append(" - Invalid URI syntax: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
            throw new RuntimeException(errorList.toString());
        } catch (IOException e) {
            errorList.append(" - IO Exception: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
            throw new RuntimeException(errorList.toString());
        } catch (NumberFormatException e) {
            errorList.append(" - Cannot parse number: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
            throw new RuntimeException(errorList.toString());
        } catch (InterruptedException e) {
            errorList.append(" - Execution interrupted: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
            throw new RuntimeException(errorList.toString());
        } catch (Exception e) {
            errorList.append(" - Unexpected error: ").append(e.getMessage()).append("\n").append(StringUtils.join(e.getStackTrace(), "\n")).append("\n");
            throw new RuntimeException(errorList.toString());
        }
        
        sampleResult.sampleEnd();
        sampleResult.setSuccessful(isOK);
        
        String logMessage = (socket != null) ? socket.getLogMessage() : "";
        sampleResult.setResponseMessage(logMessage + errorList);
        return sampleResult;
	}

    private ServiceSocket getConnectionSocket() throws URISyntaxException, Exception {
        URI uri = getUri();

        String connectionId = "TestSockets";
        ServiceSocket socket;

        //Create WebSocket client
        SslContextFactory sslContexFactory = new SslContextFactory();
        sslContexFactory.setTrustAll(true);
        WebSocketClient webSocketClient = new WebSocketClient(sslContexFactory);        
        
        if (isStreamingConnection()) {
             if (connectionList.containsKey(connectionId)) {
                 socket = connectionList.get(connectionId);
                 socket.initialize();
                 return socket;
             } else {
                socket = new ServiceSocket(this, webSocketClient);
                connectionList.put(connectionId, socket);
             }
        } else {
            socket = new ServiceSocket(this, webSocketClient);
        }

        //Start WebSocket client thread and upgrage HTTP connection
        webSocketClient.start();
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        webSocketClient.connect(socket, uri, request);
        
        //Get connection timeout or use the default value
        int connectionTimeout;
        try {
            connectionTimeout = Integer.parseInt(getConnectionTimeout());
        } catch (NumberFormatException ex) {
            log.warn("Connection timeout is not a number; using the default connection timeout of " + DEFAULT_CONNECTION_TIMEOUT + "ms");
            connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        }
        
        socket.awaitOpen(connectionTimeout, TimeUnit.MILLISECONDS);
        
        return socket;
    }
    
    public String getConnectionTimeout() {
    	return Integer.toString(DEFAULT_CONNECTION_TIMEOUT);
    }
    
    public String getResponseTimeout() {
    	return Integer.toString(DEFAULT_RESPONSE_TIMEOUT);
    }
    
    private URI getUri() throws URISyntaxException
    {
		Object[] prams = {appHost, appPort, location, boardDate};
		String appUrl = "ws" + (new MessageFormat(URL_FORMAT).format(prams));
    	return new URI(appUrl);
    }
    
	@Override
	public String getResponsePattern() {
		return this.responsePattern;
	}

	@Override
	public String getCloseConncectionPattern() {
		return this.closeConnectionPattern;
	}

	@Override
	public Boolean isStreamingConnection() {
		return this.streamingConnection;
	}

	@Override
	public String getMessageBacklog() {
		return this.messageBacklog;
	}

	/**
	 * @return the closeConnectionPattern
	 */
	public String getCloseConnectionPattern() {
		return closeConnectionPattern;
	}

	/**
	 * @return the contentEncoding
	 */
	public String getContentEncoding() {
		return contentEncoding;
	}

	/**
	 * @return the requestPayload
	 */
	public String getRequestPayload() {
		String shiftId = "280";
		String locationShiftId = UUID.randomUUID().toString();
		String requestPayload = new MessageFormat(ADD_SHIFT_MSG_FORMAT).format(new Object[]{location, boardDate, locationShiftId, shiftId});
		return requestPayload;
	}

}
