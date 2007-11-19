
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.xerox.amazonws.sqs.MessageQueue;
import com.xerox.amazonws.sqs.Message;
import com.xerox.amazonws.sqs.SQSUtils;

/**
 * This sample application creates a queue with the specified name (if the queue doesn't
 * already exist), and then sends (enqueues) a message to the queue.
 */
public class AddWork {
    private static Log logger = LogFactory.getLog(AddWork.class);

	public static void main( String[] args ) {
		try {
			if (args.length < 1) {
				logger.error("usage: AddWork <workqueue>");
				System.exit(-1);
			}

			Properties props = new Properties();
			props.load(AddWork.class.getClassLoader().getResourceAsStream("aws.properties"));

			// Create the message queue object
			MessageQueue msgQueue = SQSUtils.connectToQueue(args[0].trim(),
					props.getProperty("aws.accessId"), props.getProperty("aws.secretKey"));

			String msg = "<WorkRequest xmlns=\"http://lifeguard.dotech.com/doc/2007-06-12/\"><Project>TestProj</Project><Batch>1001</Batch><ServiceName>ingestor</ServiceName><inputBucket>testbuck</inputBucket><OutputBucket>testbuck</OutputBucket><Input>sampleS3key</Input></WorkRequest>";
			String msgId = msgQueue.sendMessage(msg);
			logger.info( "Sent message with id " + msgId );
		} catch ( Exception ex ) {
			logger.error( "EXCEPTION", ex );
		}
	}
}