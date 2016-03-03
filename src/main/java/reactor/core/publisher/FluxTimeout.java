/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.subscriber.MultiSubscriptionSubscriber;

import reactor.core.util.BackpressureUtils;
import reactor.core.util.CancelledSubscription;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;

/**
 * Signals a timeout (or switches to another sequence) in case a per-item
 * generated Publisher source fires an item or completes before the next item
 * arrives from the main source.
 *
 * @param <T> the main source type
 * @param <U> the value type for the timeout for the very first item
 * @param <V> the value type for the timeout for the subsequent items
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class FluxTimeout<T, U, V> extends FluxSource<T, T> {

	final Publisher<U> firstTimeout;

	final Function<? super T, ? extends Publisher<V>> itemTimeout;

	final Publisher<? extends T> other;

	public FluxTimeout(Publisher<? extends T> source, Publisher<U> firstTimeout,
							Function<? super T, ? extends Publisher<V>> itemTimeout) {
		super(source);
		this.firstTimeout = Objects.requireNonNull(firstTimeout, "firstTimeout");
		this.itemTimeout = Objects.requireNonNull(itemTimeout, "itemTimeout");
		this.other = null;
	}

	public FluxTimeout(Publisher<? extends T> source, Publisher<U> firstTimeout,
							Function<? super T, ? extends Publisher<V>> itemTimeout, Publisher<? extends T> other) {
		super(source);
		this.firstTimeout = Objects.requireNonNull(firstTimeout, "firstTimeout");
		this.itemTimeout = Objects.requireNonNull(itemTimeout, "itemTimeout");
		this.other = Objects.requireNonNull(other, "other");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {

		SerializedSubscriber<T> serial = new SerializedSubscriber<>(s);

		TimeoutMainSubscriber<T, V> main = new TimeoutMainSubscriber<>(serial, itemTimeout, other);

		serial.onSubscribe(main);

		TimeoutTimeoutSubscriber ts = new TimeoutTimeoutSubscriber(main, 0L);

		main.setTimeout(ts);

		firstTimeout.subscribe(ts);

		source.subscribe(main);
	}

	static final class TimeoutMainSubscriber<T, V> extends MultiSubscriptionSubscriber<T, T> {

		final Function<? super T, ? extends Publisher<V>> itemTimeout;

		final Publisher<? extends T> other;

		Subscription s;

		volatile IndexedCancellable timeout;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<TimeoutMainSubscriber, IndexedCancellable> TIMEOUT =
		  AtomicReferenceFieldUpdater.newUpdater(TimeoutMainSubscriber.class, IndexedCancellable.class,
			"timeout");

		volatile long index;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<TimeoutMainSubscriber> INDEX =
		  AtomicLongFieldUpdater.newUpdater(TimeoutMainSubscriber.class, "index");

		public TimeoutMainSubscriber(Subscriber<? super T> actual,
											  Function<? super T, ? extends Publisher<V>> itemTimeout,
											  Publisher<? extends T> other) {
			super(actual);
			this.itemTimeout = itemTimeout;
			this.other = other;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;

				set(s);
			}
		}

		@Override
		public void onNext(T t) {
			timeout.cancel();

			long idx = index;
			if (idx == Long.MIN_VALUE) {
				s.cancel();
				Exceptions.onNextDropped(t);
				return;
			}
			if (!INDEX.compareAndSet(this, idx, idx + 1)) {
				s.cancel();
				Exceptions.onNextDropped(t);
				return;
			}

			subscriber.onNext(t);

			producedOne();

			Publisher<? extends V> p;

			try {
				p = itemTimeout.apply(t);
			} catch (Throwable e) {
				cancel();
				Exceptions.throwIfFatal(e);
				subscriber.onError(Exceptions.unwrap(e));
				return;
			}

			if (p == null) {
				cancel();

				subscriber.onError(new NullPointerException("The itemTimeout returned a null Publisher"));
				return;
			}

			TimeoutTimeoutSubscriber ts = new TimeoutTimeoutSubscriber(this, idx + 1);

			if (!setTimeout(ts)) {
				return;
			}

			p.subscribe(ts);
		}

		@Override
		public void onError(Throwable t) {
			long idx = index;
			if (idx == Long.MIN_VALUE) {
				Exceptions.onErrorDropped(t);
				return;
			}
			if (!INDEX.compareAndSet(this, idx, Long.MIN_VALUE)) {
				Exceptions.onErrorDropped(t);
				return;
			}

			cancelTimeout();

			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			long idx = index;
			if (idx == Long.MIN_VALUE) {
				return;
			}
			if (!INDEX.compareAndSet(this, idx, Long.MIN_VALUE)) {
				return;
			}

			cancelTimeout();

			subscriber.onComplete();
		}

		void cancelTimeout() {
			IndexedCancellable s = timeout;
			if (s != CancelledIndexedCancellable.INSTANCE) {
				s = TIMEOUT.getAndSet(this, CancelledIndexedCancellable.INSTANCE);
				if (s != null && s != CancelledIndexedCancellable.INSTANCE) {
					s.cancel();
				}
			}
		}

		@Override
		public void cancel() {
			index = Long.MIN_VALUE;
			cancelTimeout();
			super.cancel();
		}

		boolean setTimeout(IndexedCancellable newTimeout) {

			for (; ; ) {
				IndexedCancellable currentTimeout = timeout;

				if (currentTimeout == CancelledIndexedCancellable.INSTANCE) {
					newTimeout.cancel();
					return false;
				}

				if (currentTimeout != null && currentTimeout.index() >= newTimeout.index()) {
					newTimeout.cancel();
					return false;
				}

				if (TIMEOUT.compareAndSet(this, currentTimeout, newTimeout)) {
					if (currentTimeout != null) {
						currentTimeout.cancel();
					}
					return true;
				}
			}
		}

		void doTimeout(long i) {
			if (index == i && INDEX.compareAndSet(this, i, Long.MIN_VALUE)) {
				handleTimeout();
			}
		}

		void doError(long i, Throwable e) {
			if (index == i && INDEX.compareAndSet(this, i, Long.MIN_VALUE)) {
				super.cancel();

				subscriber.onError(e);
			}
		}

		void handleTimeout() {
			if (other == null) {
				super.cancel();

				subscriber.onError(new TimeoutException());
			} else {
				set(EmptySubscription.INSTANCE);

				other.subscribe(new TimeoutOtherSubscriber<>(subscriber, this));
			}
		}
	}

	static final class TimeoutOtherSubscriber<T> implements Subscriber<T> {

		final Subscriber<? super T> actual;

		final MultiSubscriptionSubscriber<T, T> arbiter;

		public TimeoutOtherSubscriber(Subscriber<? super T> actual, MultiSubscriptionSubscriber<T, T>
		  arbiter) {
			this.actual = actual;
			this.arbiter = arbiter;
		}

		@Override
		public void onSubscribe(Subscription s) {
			arbiter.set(s);
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			actual.onComplete();
		}
	}

	interface IndexedCancellable {
		long index();

		void cancel();
	}

	enum CancelledIndexedCancellable implements IndexedCancellable {
		INSTANCE;

		@Override
		public long index() {
			return Long.MAX_VALUE;
		}

		@Override
		public void cancel() {

		}

	}

	static final class TimeoutTimeoutSubscriber implements Subscriber<Object>, IndexedCancellable {

		final TimeoutMainSubscriber<?, ?> main;

		final long index;

		volatile Subscription s;

		static final AtomicReferenceFieldUpdater<TimeoutTimeoutSubscriber, Subscription> S =
		  AtomicReferenceFieldUpdater.newUpdater(TimeoutTimeoutSubscriber.class, Subscription.class, "s");

		public TimeoutTimeoutSubscriber(TimeoutMainSubscriber<?, ?> main, long index) {
			this.main = main;
			this.index = index;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (!S.compareAndSet(this, null, s)) {
				s.cancel();
				if (this.s != CancelledSubscription.INSTANCE) {
					BackpressureUtils.reportSubscriptionSet();
				}
			} else {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(Object t) {
			s.cancel();

			main.doTimeout(index);
		}

		@Override
		public void onError(Throwable t) {
			main.doError(index, t);
		}

		@Override
		public void onComplete() {
			main.doTimeout(index);
		}

		@Override
		public void cancel() {
			Subscription a = s;
			if (a != CancelledSubscription.INSTANCE) {
				a = S.getAndSet(this, CancelledSubscription.INSTANCE);
				if (a != null && a != CancelledSubscription.INSTANCE) {
					a.cancel();
				}
			}
		}

		@Override
		public long index() {
			return index;
		}
	}
}
