/**
 * 
 */
package JMeter.plugins.functional.samplers.websocket;

/**
 * @author nasangameshwaran
 *
 */
public interface SocketSampler {
	String getResponsePattern();
	String getCloseConncectionPattern();
	Boolean isStreamingConnection();
	String getMessageBacklog();
}
