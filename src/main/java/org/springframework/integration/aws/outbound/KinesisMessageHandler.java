/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.aws.outbound;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.MessageTimeoutException;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.aws.support.AwsRequestFailureException;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.expression.ValueExpression;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.handler.AbstractMessageProducingHandler;
import org.springframework.integration.support.AbstractIntegrationMessageBuilder;
import org.springframework.integration.support.DefaultErrorMessageStrategy;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResult;
import com.amazonaws.ResponseMetadata;
import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordResult;
import com.amazonaws.services.kinesis.model.PutRecordsRequest;
import com.amazonaws.services.kinesis.model.PutRecordsResult;

/**
 * The {@link AbstractMessageHandler} implementation for the Amazon Kinesis {@code putRecord(s)}.
 *
 * @author Artem Bilan
 * @author Jacob Severson
 *
 * @since 1.1
 *
 * @see AmazonKinesisAsync#putRecord(PutRecordRequest)
 * @see AmazonKinesisAsync#putRecords(PutRecordsRequest)
 * @see com.amazonaws.handlers.AsyncHandler
 */
public class KinesisMessageHandler extends AbstractMessageProducingHandler {

	private static final long DEFAULT_SEND_TIMEOUT = 10000;

	private final AmazonKinesisAsync amazonKinesis;

	private AsyncHandler<? extends AmazonWebServiceRequest, ?> asyncHandler;

	private Converter<Object, byte[]> converter = new SerializingConverter();

	private EvaluationContext evaluationContext;

	private volatile Expression streamExpression;

	private volatile Expression partitionKeyExpression;

	private volatile Expression explicitHashKeyExpression;

	private volatile Expression sequenceNumberExpression;

	private boolean sync;

	private Expression sendTimeoutExpression = new ValueExpression<>(DEFAULT_SEND_TIMEOUT);

	private MessageChannel failureChannel;

	private String failureChannelName;

	private ErrorMessageStrategy errorMessageStrategy = new DefaultErrorMessageStrategy();

	public KinesisMessageHandler(AmazonKinesisAsync amazonKinesis) {
		Assert.notNull(amazonKinesis, "'amazonKinesis' must not be null.");
		this.amazonKinesis = amazonKinesis;
	}

	public void setAsyncHandler(AsyncHandler<? extends AmazonWebServiceRequest, ?> asyncHandler) {
		this.asyncHandler = asyncHandler;
	}

	/**
	 * Specify a {@link Converter} to serialize {@code payload} to the {@code byte[]}
	 * if that isn't {@code byte[]} already.
	 * @param converter the {@link Converter} to use; cannot be null.
	 */
	public void setConverter(Converter<Object, byte[]> converter) {
		Assert.notNull(converter, "'converter' must not be null.");
		this.converter = converter;
	}

	public void setStream(String stream) {
		setStreamExpression(new LiteralExpression(stream));
	}

	public void setStreamExpressionString(String streamExpression) {
		setStreamExpression(EXPRESSION_PARSER.parseExpression(streamExpression));
	}

	public void setStreamExpression(Expression streamExpression) {
		this.streamExpression = streamExpression;
	}

	public void setPartitionKey(String partitionKey) {
		setPartitionKeyExpression(new LiteralExpression(partitionKey));
	}

	public void setPartitionKeyExpressionString(String partitionKeyExpression) {
		setPartitionKeyExpression(EXPRESSION_PARSER.parseExpression(partitionKeyExpression));
	}

	public void setPartitionKeyExpression(Expression partitionKeyExpression) {
		this.partitionKeyExpression = partitionKeyExpression;
	}

	public void setExplicitHashKey(String explicitHashKey) {
		setExplicitHashKeyExpression(new LiteralExpression(explicitHashKey));
	}

	public void setExplicitHashKeyExpressionString(String explicitHashKeyExpression) {
		setExplicitHashKeyExpression(EXPRESSION_PARSER.parseExpression(explicitHashKeyExpression));
	}

	public void setExplicitHashKeyExpression(Expression explicitHashKeyExpression) {
		this.explicitHashKeyExpression = explicitHashKeyExpression;
	}

	public void setSequenceNumberExpressionString(String sequenceNumberExpression) {
		setSequenceNumberExpression(EXPRESSION_PARSER.parseExpression(sequenceNumberExpression));
	}

	public void setSequenceNumberExpression(Expression sequenceNumberExpression) {
		this.sequenceNumberExpression = sequenceNumberExpression;
	}

	public void setSync(boolean sync) {
		this.sync = sync;
	}

	public void setSendTimeout(long sendTimeout) {
		setSendTimeoutExpression(new ValueExpression<>(sendTimeout));
	}

	public void setSendTimeoutExpressionString(String sendTimeoutExpression) {
		setSendTimeoutExpression(EXPRESSION_PARSER.parseExpression(sendTimeoutExpression));
	}

	public void setSendTimeoutExpression(Expression sendTimeoutExpression) {
		this.sendTimeoutExpression = sendTimeoutExpression;
	}

	/**
	 * Set the failure channel. After a failure on put, an {@link ErrorMessage} will be sent
	 * to this channel with a payload of a {@link AwsRequestFailureException} with the
	 * failed message and cause.
	 * @param failureChannel the failure channel.
	 * @since 1.1.0
	 */
	public void setFailureChannel(MessageChannel failureChannel) {
		this.failureChannel = failureChannel;
	}

	protected MessageChannel getFailureChannel() {
		if (this.failureChannel != null) {
			return this.failureChannel;

		}
		else if (this.failureChannelName != null) {
			this.failureChannel = getChannelResolver().resolveDestination(this.failureChannelName);
			return this.failureChannel;
		}

		return null;
	}

	/**
	 * Set the failure channel name. After a failure on put, an {@link ErrorMessage} will be
	 * sent to this channel name with a payload of a {@link AwsRequestFailureException}
	 * with the failed message and cause.
	 * @param failureChannelName the failure channel name.
	 * @since 1.1.0
	 */
	public void setFailureChannelName(String failureChannelName) {
		this.failureChannelName = failureChannelName;
	}

	@Override
	protected void onInit() throws Exception {
		super.onInit();
		this.evaluationContext = ExpressionUtils.createStandardEvaluationContext(getBeanFactory());
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void handleMessageInternal(final Message<?> message) throws Exception {
		Future<?> resultFuture;

		if (message.getPayload() instanceof PutRecordsRequest) {

			AsyncHandler<PutRecordsRequest, PutRecordsResult> asyncHandler =
					obtainAsyncHandler(message, (PutRecordsRequest) message.getPayload());

			resultFuture = this.amazonKinesis.putRecordsAsync((PutRecordsRequest) message.getPayload(), asyncHandler);
		}
		else {
			final PutRecordRequest putRecordRequest = (message.getPayload() instanceof PutRecordRequest)
					? (PutRecordRequest) message.getPayload()
					: buildPutRecordRequest(message);

			AsyncHandler<PutRecordRequest, PutRecordResult> asyncHandler =
					obtainAsyncHandler(message, putRecordRequest);

			resultFuture = this.amazonKinesis.putRecordAsync(putRecordRequest, asyncHandler);
		}

		if (this.sync) {
			Long sendTimeout = this.sendTimeoutExpression.getValue(this.evaluationContext, message, Long.class);
			if (sendTimeout == null || sendTimeout < 0) {
				resultFuture.get();
			}
			else {
				try {
					resultFuture.get(sendTimeout, TimeUnit.MILLISECONDS);
				}
				catch (TimeoutException te) {
					throw new MessageTimeoutException(message, "Timeout waiting for response from AmazonKinesis", te);
				}
			}
		}
	}

	private PutRecordRequest buildPutRecordRequest(Message<?> message) {
		String stream = message.getHeaders().get(AwsHeaders.STREAM, String.class);
		if (!StringUtils.hasText(stream) && this.streamExpression != null) {
			stream = this.streamExpression.getValue(this.evaluationContext, message, String.class);
		}
		Assert.state(stream != null, "'stream' must not be null for sending a Kinesis record. " +
				"Consider configuring this handler with a 'stream'( or 'streamExpression') or supply an " +
				"'aws_stream' message header.");

		String partitionKey = message.getHeaders().get(AwsHeaders.PARTITION_KEY, String.class);
		if (!StringUtils.hasText(partitionKey) && this.partitionKeyExpression != null) {
			partitionKey = this.partitionKeyExpression.getValue(this.evaluationContext, message, String.class);
		}
		Assert.state(partitionKey != null, "'partitionKey' must not be null for sending a Kinesis record. " +
				"Consider configuring this handler with a 'partitionKey'( or 'partitionKeyExpression') or supply an " +
				"'aws_partitionKey' message header.");

		String explicitHashKey =
				(this.explicitHashKeyExpression != null
						? this.explicitHashKeyExpression.getValue(this.evaluationContext, message, String.class)
						: null);

		String sequenceNumber = message.getHeaders().get(AwsHeaders.SEQUENCE_NUMBER, String.class);
		if (!StringUtils.hasText(stream) && this.streamExpression != null) {
			partitionKey = this.sequenceNumberExpression.getValue(this.evaluationContext, message, String.class);
		}

		Object payload = message.getPayload();

		ByteBuffer data;

		if (payload instanceof ByteBuffer) {
			data = (ByteBuffer) payload;
		}
		else {
			byte[] bytes =
					payload instanceof byte[]
							? (byte[]) payload
							: this.converter.convert(payload);

			data = ByteBuffer.wrap(bytes);
		}

		return new PutRecordRequest()
				.withStreamName(stream)
				.withPartitionKey(partitionKey)
				.withExplicitHashKey(explicitHashKey)
				.withSequenceNumberForOrdering(sequenceNumber)
				.withData(data);
	}

	@SuppressWarnings("rawtypes")
	private <REQUEST extends AmazonWebServiceRequest, RESULT extends AmazonWebServiceResult<?extends ResponseMetadata>> AsyncHandler<REQUEST, RESULT> obtainAsyncHandler(
			final Message<?> message, final REQUEST request) {

		return new AsyncHandler<REQUEST, RESULT>() {

			@Override
			public void onError(Exception ex) {
				if (KinesisMessageHandler.this.asyncHandler != null) {
					KinesisMessageHandler.this.asyncHandler.onError(ex);
				}

				if (getFailureChannel() != null) {
					KinesisMessageHandler.this.messagingTemplate.send(getFailureChannel(),
							KinesisMessageHandler.this.errorMessageStrategy.buildErrorMessage(
									new AwsRequestFailureException(message, request, ex), null));
				}
			}

			@Override
			@SuppressWarnings("unchecked")
			public void onSuccess(REQUEST request, RESULT result) {
				if (KinesisMessageHandler.this.asyncHandler != null) {
					((AsyncHandler<REQUEST, RESULT>) KinesisMessageHandler.this.asyncHandler)
							.onSuccess(request, result);
				}

				if (getOutputChannel() != null) {
					AbstractIntegrationMessageBuilder<?> messageBuilder =
							getMessageBuilderFactory()
									.fromMessage(message);

					if (result instanceof PutRecordResult) {
						messageBuilder
								.setHeader(AwsHeaders.SHARD, ((PutRecordResult) result).getShardId())
								.setHeader(AwsHeaders.SEQUENCE_NUMBER, ((PutRecordResult) result).getSequenceNumber());
					}
					else {
						messageBuilder.setHeader(AwsHeaders.SERVICE_RESULT, result);
					}


					KinesisMessageHandler.this.messagingTemplate.send(getOutputChannel(), messageBuilder.build());
				}
			}

		};
	}

}

