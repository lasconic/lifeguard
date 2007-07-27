
package com.directthought.lifeguard;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.File;
import java.util.List;

import javax.xml.bind.JAXBException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;

import com.xerox.amazonws.common.JAXBuddy;
import com.xerox.amazonws.sqs.Message;
import com.xerox.amazonws.sqs.MessageQueue;
import com.xerox.amazonws.sqs.QueueService;
import com.xerox.amazonws.sqs.SQSException;

import com.directthought.lifeguard.jaxb.ServiceConfig;
import com.directthought.lifeguard.jaxb.Step;
import com.directthought.lifeguard.jaxb.WorkRequest;
import com.directthought.lifeguard.jaxb.WorkStatus;

public abstract class AbstractBaseService implements Runnable {
	private static Log logger = LogFactory.getLog(AbstractBaseService.class);
	private ServiceConfig config;
	private String accessId;
	private String secretKey;

	public AbstractBaseService(ServiceConfig config, String accessId, String secretKey) {
		this.config = config;
		this.accessId = accessId;
		this.secretKey = secretKey;
	}

	public abstract String getServiceName();

	public abstract List<MetaFile> executeService(File inputFile, WorkRequest request);

	public class MetaFile {
		public File file;
		public String mimeType;

		public MetaFile(File file, String mimeType) {
			this.file = file;
			this.mimeType = mimeType;
		}
	}

	public void run() {
		try {
			// connect to queues
			QueueService qs = new QueueService(accessId, secretKey);
			MessageQueue statusQueue = QueueUtil.getQueueOrElse(qs, config.getPoolStatusQueue());
			MessageQueue workQueue = QueueUtil.getQueueOrElse(qs, config.getServiceWorkQueue());

			while (true) {
				// read work queue
				Message msg = null;
				try {
					msg = workQueue.receiveMessage();
				} catch (SQSException ex) {
					logger.error("Error reading message, Retrying.", ex);
				}
				if (msg == null) {
					try { Thread.sleep(2000); } catch (InterruptedException ex) {}
					continue;
				}
				// parse work
				try {
					long startTime = System.currentTimeMillis();
					WorkRequest request = JAXBuddy.deserializeXMLStream(WorkRequest.class,
									new ByteArrayInputStream(msg.getMessageBody().getBytes()));
					// pull file from S3
					RestS3Service s3 = new RestS3Service(new AWSCredentials(accessId, secretKey));
					S3Bucket inBucket = new S3Bucket(request.getInputBucket());
					S3Object obj = s3.getObject(inBucket, request.getInput().getKey());
					InputStream iStr = obj.getDataInputStream();
					// should convert from mime-type to extension
					File inputFile = File.createTempFile("lg", ".dat", new File("."));
					byte [] buf = new byte[64*1024];	// 64k i/o buffer
					FileOutputStream oStr = new FileOutputStream(inputFile);
					int count = iStr.read(buf);
					while (count != -1) {
						if (count > 0) {
							oStr.write(buf, 0, count);
						}
						count = iStr.read(buf);
					}
					// call executeService()
					List<MetaFile> results = executeService(inputFile, request);
					inputFile.delete();
					// send results to S3
					for (MetaFile file : results) {
						S3Bucket outBucket = new S3Bucket(request.getOutputBucket());
						obj = new S3Object(outBucket, file.file);
						obj = s3.putObject(outBucket, obj);
					}
					// after all transferred, delete them locally
					for (MetaFile file : results) {
						file.file.delete();
					}
					long endTime = System.currentTimeMillis();
					// create status
					WorkStatus ws = MessageHelper.createWorkStatus(request, inputFile.getName(), startTime, endTime, "localhost");
					// send next work request
					Step next = request.getNextStep();
					if (next != null) {
						MessageQueue nextQueue = QueueUtil.getQueueOrElse(qs, next.getWorkQueue());
						String mimeType = next.getType();
						for (MetaFile file : results) {
							if (file.mimeType.equals(mimeType)) {
								request.getInput().setType(mimeType);
								request.getInput().setKey(file.file.getName());
							}
						}
						request.setNextStep(next.getNextStep());
						String message = JAXBuddy.serializeXMLString(WorkRequest.class, request);
						nextQueue.sendMessage(message);
					}
					// send status
					String message = JAXBuddy.serializeXMLString(WorkStatus.class, ws);
					statusQueue.sendMessage(message);
				} catch (JAXBException ex) {
					logger.error("Problem parsing work request!", ex);
				}
				workQueue.deleteMessage(msg);
			}
		} catch (Throwable t) {
			logger.error("Something unexpected happened in the "+getServiceName()+" service", t);
		}
	}
}
