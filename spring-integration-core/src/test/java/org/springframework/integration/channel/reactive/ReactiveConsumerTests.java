/*
 * Copyright 2016-2017 the original author or authors.
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

package org.springframework.integration.channel.reactive;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.channel.ReactiveChannel;
import org.springframework.integration.config.ConsumerEndpointFactoryBean;
import org.springframework.integration.endpoint.ReactiveConsumer;
import org.springframework.integration.handler.MethodInvokingMessageHandler;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.GenericMessage;

import reactor.core.publisher.EmitterProcessor;

/**
 * @author Artem Bilan
 * @since 5.0
 */
public class ReactiveConsumerTests {

	@Test
	public void testReactiveConsumerReactiveChannel() throws InterruptedException {
		ReactiveChannel testChannel = new ReactiveChannel(EmitterProcessor.create(false));

		List<Message<?>> result = new LinkedList<>();
		CountDownLatch stopLatch = new CountDownLatch(2);

		MessageHandler messageHandler = m -> {
			result.add(m);
			stopLatch.countDown();
		};

		MethodInvokingMessageHandler testSubscriber = new MethodInvokingMessageHandler(messageHandler, (String) null);

		ReactiveConsumer reactiveConsumer = new ReactiveConsumer(testChannel, testSubscriber);
		reactiveConsumer.setBeanFactory(mock(BeanFactory.class));
		reactiveConsumer.afterPropertiesSet();
		reactiveConsumer.start();

		Message<?> testMessage = new GenericMessage<>("test");
		testChannel.send(testMessage);

		reactiveConsumer.stop();

		testChannel.send(testMessage);

		reactiveConsumer.start();

		Message<?> testMessage2 = new GenericMessage<>("test2");
		testChannel.send(testMessage2);

		assertTrue(stopLatch.await(10, TimeUnit.SECONDS));
		assertThat(result, Matchers.<Message<?>>contains(testMessage, testMessage2));
	}


	@Test
	@SuppressWarnings("unchecked")
	public void testReactiveConsumerDirectChannel() throws InterruptedException {
		DirectChannel testChannel = new DirectChannel();

		Subscriber<Message<?>> testSubscriber = (Subscriber<Message<?>>) Mockito.mock(Subscriber.class);

		BlockingQueue<Message<?>> messages = new LinkedBlockingQueue<>();

		willAnswer(i -> {
			messages.put(i.getArgument(0));
			return null;
		})
				.given(testSubscriber)
				.onNext(any(Message.class));

		ReactiveConsumer reactiveConsumer = new ReactiveConsumer(testChannel, testSubscriber);
		reactiveConsumer.setBeanFactory(mock(BeanFactory.class));
		reactiveConsumer.afterPropertiesSet();
		reactiveConsumer.start();

		Message<?> testMessage = new GenericMessage<>("test");
		testChannel.send(testMessage);

		ArgumentCaptor<Subscription> subscriptionArgumentCaptor = ArgumentCaptor.forClass(Subscription.class);
		verify(testSubscriber).onSubscribe(subscriptionArgumentCaptor.capture());
		Subscription subscription = subscriptionArgumentCaptor.getValue();

		subscription.request(1);

		Message<?> message = messages.poll(10, TimeUnit.SECONDS);
		assertSame(testMessage, message);

		reactiveConsumer.stop();

		try {
			testChannel.send(testMessage);
			fail("MessageDeliveryException");
		}
		catch (Exception e) {
			assertThat(e, instanceOf(MessageDeliveryException.class));
		}

		reactiveConsumer.start();

		subscription.request(1);

		testMessage = new GenericMessage<>("test2");

		testChannel.send(testMessage);

		message = messages.poll(10, TimeUnit.SECONDS);
		assertSame(testMessage, message);

		verify(testSubscriber, never()).onError(any(Throwable.class));
		verify(testSubscriber, never()).onComplete();

		assertTrue(messages.isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testReactiveConsumerPollableChannel() throws InterruptedException {
		QueueChannel testChannel = new QueueChannel();

		Subscriber<Message<?>> testSubscriber = (Subscriber<Message<?>>) Mockito.mock(Subscriber.class);

		BlockingQueue<Message<?>> messages = new LinkedBlockingQueue<>();

		willAnswer(i -> {
			messages.put(i.getArgument(0));
			return null;
		})
				.given(testSubscriber)
				.onNext(any(Message.class));

		ReactiveConsumer reactiveConsumer = new ReactiveConsumer(testChannel, testSubscriber);
		reactiveConsumer.setBeanFactory(mock(BeanFactory.class));
		reactiveConsumer.afterPropertiesSet();
		reactiveConsumer.start();

		Message<?> testMessage = new GenericMessage<>("test");
		testChannel.send(testMessage);

		ArgumentCaptor<Subscription> subscriptionArgumentCaptor = ArgumentCaptor.forClass(Subscription.class);
		verify(testSubscriber).onSubscribe(subscriptionArgumentCaptor.capture());
		Subscription subscription = subscriptionArgumentCaptor.getValue();

		subscription.request(1);

		Message<?> message = messages.poll(10, TimeUnit.SECONDS);
		assertSame(testMessage, message);

		reactiveConsumer.stop();


		testChannel.send(testMessage);

		reactiveConsumer.start();

		subscription.request(2);

		Message<?> testMessage2 = new GenericMessage<>("test2");

		testChannel.send(testMessage2);

		message = messages.poll(10, TimeUnit.SECONDS);
		assertSame(testMessage, message);

		message = messages.poll(10, TimeUnit.SECONDS);
		assertSame(testMessage2, message);

		verify(testSubscriber, never()).onError(any(Throwable.class));
		verify(testSubscriber, never()).onComplete();

		assertTrue(messages.isEmpty());
	}

	@Test
	public void testReactiveConsumerViaConsumerEndpointFactoryBean() throws Exception {
		ReactiveChannel testChannel = new ReactiveChannel();

		List<Message<?>> result = new LinkedList<>();
		CountDownLatch stopLatch = new CountDownLatch(3);

		MessageHandler messageHandler = m -> {
			result.add(m);
			stopLatch.countDown();
		};

		ConsumerEndpointFactoryBean endpointFactoryBean = new ConsumerEndpointFactoryBean();
		endpointFactoryBean.setBeanFactory(mock(ConfigurableBeanFactory.class));
		endpointFactoryBean.setInputChannel(testChannel);
		endpointFactoryBean.setHandler(messageHandler);
		endpointFactoryBean.setBeanName("reactiveConsumer");
		endpointFactoryBean.afterPropertiesSet();
		endpointFactoryBean.start();

		Message<?> testMessage = new GenericMessage<>("test");
		testChannel.send(testMessage);

		endpointFactoryBean.stop();

		testChannel.send(testMessage);

		endpointFactoryBean.start();

		Message<?> testMessage2 = new GenericMessage<>("test2");

		testChannel.send(testMessage2);
		testChannel.send(testMessage2);

		assertTrue(stopLatch.await(10, TimeUnit.SECONDS));
		assertThat(result.size(), equalTo(3));
		assertThat(result, Matchers.<Message<?>>contains(testMessage, testMessage2, testMessage2));
	}

}
