/*
 * Copyright (c) 2016-2023 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;

import org.reactivestreams.Publisher;

import reactor.core.CorePublisher;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.util.annotation.Nullable;

/**
 * Emits a single item at most from the source.
 *
 * @param <T> the value type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class MonoFromPublisher<T> extends Mono<T> implements Scannable,
                                                            OptimizableOperator<T, T> {

	final Publisher<? extends T> source;

	@Nullable
	final OptimizableOperator<?, T> optimizableOperator;

	MonoFromPublisher(Publisher<? extends T> source) {
		this.source = Objects.requireNonNull(source, "publisher");
		if (source instanceof OptimizableOperator) {
			@SuppressWarnings("unchecked")
			OptimizableOperator<?, T> optimSource = (OptimizableOperator<?, T>) source;
			this.optimizableOperator = optimSource;
		}
		else {
			this.optimizableOperator = null;
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(CoreSubscriber<? super T> actual) {
		if (ContextPropagationSupport.shouldPropagateContextToThreadLocals()) {
			actual = new MonoSource.MonoSourceRestoringThreadLocalsSubscriber<>(actual);
		}

		try {
			CoreSubscriber<? super T> subscriber = subscribeOrReturn(actual);
			if (subscriber == null) {
				return;
			}
			source.subscribe(subscriber);
		}
		catch (Throwable e) {
			Operators.error(actual, Operators.onOperatorError(e, actual.currentContext()));
			return;
		}
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) throws Throwable {
		return new MonoNext.NextSubscriber<>(actual);
	}

	@Override
	public final CorePublisher<? extends T> source() {
		return this;
	}

	@Override
	public final OptimizableOperator<?, ? extends T> nextOptimizableSource() {
		return optimizableOperator;
	}

	@Override
	@Nullable
	public Object scanUnsafe(Scannable.Attr key) {
		if (key == Scannable.Attr.PARENT) {
			return source;
		}
		if (key == Scannable.Attr.RUN_STYLE) {
		    return Attr.RunStyle.SYNC;
		}
		return null;
	}
}
