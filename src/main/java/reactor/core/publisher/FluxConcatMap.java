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
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.Fuseable;

/**
 * Maps each upstream value into a Publisher and concatenates them into one
 * sequence of items.
 * 
 * @param <T> the source value type
 * @param <R> the output value type
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxConcatMap<T, R> extends FluxSource<T, R> {
	
	final Function<? super T, ? extends Publisher<? extends R>> mapper;
	
	final Supplier<? extends Queue<T>> queueSupplier;
	
	final int prefetch;
	
	final ErrorMode errorMode;
	
	/**
	 * Indicates when an error from the main source should be reported.
	 */
	public enum ErrorMode {
		/** Report the error immediately, cancelling the active inner source. */
		IMMEDIATE,
		/** Report error after an inner source terminated. */
		BOUNDARY,
		/** Report the error after all sources terminated. */
		END
	}

	public static <T, R> Subscriber<T> subscriber(Subscriber<? super R> s, Function<?
			super T, ? extends Publisher<? extends R>> mapper,
			Supplier<? extends Queue<T>> queueSupplier,
			int prefetch, ErrorMode errorMode) {
		Subscriber<T> parent;
		switch (errorMode) {
			case BOUNDARY:
				parent = new ConcatMapDelayed<>(s, mapper, queueSupplier, prefetch,
						false);
				break;
			case END:
				parent = new ConcatMapDelayed<>(s, mapper, queueSupplier, prefetch, true);
				break;
			default:
				parent = new ConcatMapImmediate<>(s, mapper, queueSupplier, prefetch);
		}
		return parent;
	}

	public FluxConcatMap(Publisher<? extends T> source,
			Function<? super T, ? extends Publisher<? extends R>> mapper, 
			Supplier<? extends Queue<T>> queueSupplier,
			int prefetch, ErrorMode errorMode) {
		super(source);
		if (prefetch <= 0) {
			throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
		}
		this.mapper = Objects.requireNonNull(mapper, "mapper");
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
		this.prefetch = prefetch;
		this.errorMode = Objects.requireNonNull(errorMode, "errorMode");
	}

	@Override
	public void subscribe(Subscriber<? super R> s) {

		if (FluxFlatMap.trySubscribeScalarMap(source, s, mapper, false)) {
			return;
		}
		
		Subscriber<T> parent;
		switch (errorMode) {
		case BOUNDARY:
			parent = new ConcatMapDelayed<>(s, mapper, queueSupplier, prefetch, false);
			break;
		case END:
			parent = new ConcatMapDelayed<>(s, mapper, queueSupplier, prefetch, true);
			break;
		default:
			parent = new ConcatMapImmediate<>(s, mapper, queueSupplier, prefetch);
		}
		source.subscribe(parent);
	}

	static final class ConcatMapImmediate<T, R>
			implements Subscriber<T>, FluxConcatMapSupport<R>, Subscription {

		final Subscriber<? super R> actual;
		
		final ConcatMapInner<R> inner;
		
		final Function<? super T, ? extends Publisher<? extends R>> mapper;
		
		final Supplier<? extends Queue<T>> queueSupplier;
		
		final int prefetch;

		final int limit;
		
		Subscription s;

		int consumed;
		
		volatile Queue<T> queue;
		
		volatile boolean done;
		
		volatile boolean cancelled;
		
		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ConcatMapImmediate, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(ConcatMapImmediate.class, Throwable.class, "error");
		
		volatile boolean active;
		
		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatMapImmediate> WIP =
				AtomicIntegerFieldUpdater.newUpdater(ConcatMapImmediate.class, "wip");

		volatile int guard;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatMapImmediate> GUARD =
				AtomicIntegerFieldUpdater.newUpdater(ConcatMapImmediate.class, "guard");

		int sourceMode;
		
		static final int SYNC = 1;
		static final int ASYNC = 2;
		
		public ConcatMapImmediate(Subscriber<? super R> actual,
				Function<? super T, ? extends Publisher<? extends R>> mapper,
				Supplier<? extends Queue<T>> queueSupplier, int prefetch) {
			this.actual = actual;
			this.mapper = mapper;
			this.queueSupplier = queueSupplier;
			this.prefetch = prefetch;
			this.limit = prefetch - (prefetch >> 2);
			this.inner = new ConcatMapInner<>(this);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s))  {
				this.s = s;

				if (s instanceof Fuseable.QueueSubscription) {
					@SuppressWarnings("unchecked") Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>)s;
					int m = f.requestFusion(Fuseable.ANY);
					if (m == Fuseable.SYNC){
						sourceMode = SYNC;
						queue = f;
						done = true;
						
						actual.onSubscribe(this);
						
						drain();
						return;
					} else 
					if (m == Fuseable.ASYNC) {
						sourceMode = ASYNC;
						queue = f;
					} else {
						try {
							queue = queueSupplier.get();
						} catch (Throwable ex) {
							Operators.error(actual, Operators.onOperatorError(s, ex));
							return;
						}
					}
				} else {
					try {
						queue = queueSupplier.get();
					} catch (Throwable ex) {
						s.cancel();

						Operators.error(actual, ex);
						return;
					}
				}
				
				actual.onSubscribe(this);
				
				s.request(prefetch);
			}
		}
		
		@Override
		public void onNext(T t) {
			if (sourceMode == ASYNC) {
				drain();
			} else
			if (!queue.offer(t)) {
				s.cancel();
				onError(new IllegalStateException("Queue full?!"));
			} else {
				drain();
			}
		}
		
		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				inner.cancel();
				
				if (GUARD.getAndIncrement(this) == 0) {
					t = Exceptions.terminate(ERROR, this);
					if (t != Exceptions.TERMINATED) {
						actual.onError(t);
					}
				}
			} else {
				Operators.onErrorDropped(t);
			}
		}
		
		@Override
		public void onComplete() {
			done = true;
			drain();
		}
		
		@Override
		public void innerNext(R value) {
			if (guard == 0 && GUARD.compareAndSet(this, 0, 1)) {
				actual.onNext(value);
				if (GUARD.compareAndSet(this, 1, 0)) {
					return;
				}
				Throwable e = Exceptions.terminate(ERROR, this);
				if (e != Exceptions.TERMINATED) {
					actual.onError(e);
				}
			}
		}
		
		@Override
		public void innerComplete() {
			active = false;
			drain();
		}
		
		@Override
		public void innerError(Throwable e) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				s.cancel();
				
				if (GUARD.getAndIncrement(this) == 0) {
					e = Exceptions.terminate(ERROR, this);
					if (e != Exceptions.TERMINATED) {
						actual.onError(e);
					}
				}
			} else {
				Operators.onErrorDropped(e);
			}
		}
		
		@Override
		public void request(long n) {
			inner.request(n);
		}
		
		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				
				inner.cancel();
				s.cancel();
			}
		}
		
		void drain() {
			if (WIP.getAndIncrement(this) == 0) {
				for (;;) {
					if (cancelled) {
						return;
					}
					
					if (!active) {
						boolean d = done;
						
						T v;
						
						try {
							v = queue.poll();
						} catch (Throwable e) {
							actual.onError(Operators.onOperatorError(s, e));
							return;
						}
						
						boolean empty = v == null;
						
						if (d && empty) {
							actual.onComplete();
							return;
						}
						
						if (!empty) {
							Publisher<? extends R> p;
							
							try {
								p = mapper.apply(v);
							} catch (Throwable e) {
								actual.onError(Operators.onOperatorError(s, e, v));
								return;
							}
							
							if (p == null) {
								actual.onError(Operators.onOperatorError(s,
										new NullPointerException("The mapper returned a " + "null Publisher"),
										v));
								return;
							}
							
							if (sourceMode != SYNC) {
								int c = consumed + 1;
								if (c == limit) {
									consumed = 0;
									s.request(c);
								} else {
									consumed = c;
								}
							}


							if (p instanceof Callable) {
								@SuppressWarnings("unchecked") Callable<R> callable =
										(Callable<R>) p;
								
								R vr;
								
								try {
									vr = callable.call();
								} catch (Throwable e) {
									actual.onError(Operators.onOperatorError(s, e, v));
									return;
								}
								
								
								if (vr == null) {
									continue;
								}
								
								if (inner.isUnbounded()) {
									if (guard == 0 && GUARD.compareAndSet(this, 0, 1)) {
										actual.onNext(vr);
										if (!GUARD.compareAndSet(this, 1, 0)) {
											Throwable e = Exceptions.terminate(ERROR, this);
											if (e != Exceptions.TERMINATED) {
												actual.onError(e);
											}
											return;
										}
									}
									continue;
								} else {
									active = true;
									inner.set(new WeakScalarSubscription<>(vr, inner));
								}
								
							} else {
								active = true;
								p.subscribe(inner);
							}
						}
					}
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
				}
			}
		}
	}
	
	static final class WeakScalarSubscription<T> implements Subscription {
		final Subscriber<? super T> actual;
		final T value;
		boolean once;

		public WeakScalarSubscription(T value, Subscriber<? super T> actual) {
			this.value = value;
			this.actual = actual;
		}
		
		@Override
		public void request(long n) {
			if (n > 0 && !once) {
				once = true;
				Subscriber<? super T> a = actual;
				a.onNext(value);
				a.onComplete();
			}
		}
		
		@Override
		public void cancel() {
			
		}
	}

	static final class ConcatMapDelayed<T, R>
			implements Subscriber<T>, FluxConcatMapSupport<R>, Subscription {

		final Subscriber<? super R> actual;
		
		final ConcatMapInner<R> inner;
		
		final Function<? super T, ? extends Publisher<? extends R>> mapper;
		
		final Supplier<? extends Queue<T>> queueSupplier;
		
		final int prefetch;

		final int limit;
		
		final boolean veryEnd;
		
		Subscription s;

		int consumed;
		
		volatile Queue<T> queue;
		
		volatile boolean done;
		
		volatile boolean cancelled;
		
		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<ConcatMapDelayed, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(ConcatMapDelayed.class, Throwable.class, "error");
		
		volatile boolean active;
		
		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<ConcatMapDelayed> WIP =
				AtomicIntegerFieldUpdater.newUpdater(ConcatMapDelayed.class, "wip");

		int sourceMode;
		
		static final int SYNC = 1;
		static final int ASYNC = 2;
		
		public ConcatMapDelayed(Subscriber<? super R> actual,
				Function<? super T, ? extends Publisher<? extends R>> mapper,
				Supplier<? extends Queue<T>> queueSupplier, int prefetch, boolean veryEnd) {
			this.actual = actual;
			this.mapper = mapper;
			this.queueSupplier = queueSupplier;
			this.prefetch = prefetch;
			this.limit = prefetch - (prefetch >> 2);
			this.veryEnd = veryEnd;
			this.inner = new ConcatMapInner<>(this);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s))  {
				this.s = s;

				if (s instanceof Fuseable.QueueSubscription) {
					@SuppressWarnings("unchecked") Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>)s;
					
					int m = f.requestFusion(Fuseable.ANY);
					
					if (m == Fuseable.SYNC){
						sourceMode = SYNC;
						queue = f;
						done = true;
						
						actual.onSubscribe(this);
						
						drain();
						return;
					} else 
					if (m == Fuseable.ASYNC) {
						sourceMode = ASYNC;
						queue = f;
					} else {
						try {
							queue = queueSupplier.get();
						} catch (Throwable ex) {
							Operators.error(actual, Operators.onOperatorError(s, ex));
							return;
						}
					}
				} else {
					try {
						queue = queueSupplier.get();
					} catch (Throwable ex) {
						Operators.error(actual, Operators.onOperatorError(s, ex));
						return;
					}
				}
				
				actual.onSubscribe(this);
				
				s.request(prefetch);
			}
		}
		
		@Override
		public void onNext(T t) {
			if (sourceMode == ASYNC) {
				drain();
			} else
			if (!queue.offer(t)) {
				s.cancel();
				onError(new IllegalStateException("Queue full?!"));
			} else {
				drain();
			}
		}
		
		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				done = true;
				drain();
			} else {
				Operators.onErrorDropped(t);
			}
		}
		
		@Override
		public void onComplete() {
			done = true;
			drain();
		}
		
		@Override
		public void innerNext(R value) {
			actual.onNext(value);
		}
		
		@Override
		public void innerComplete() {
			active = false;
			drain();
		}
		
		@Override
		public void innerError(Throwable e) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				if (!veryEnd) {
					s.cancel();
					done = true;
				}
				active = false;
				drain();
			} else {
				Operators.onErrorDropped(e);
			}
		}
		
		@Override
		public void request(long n) {
			inner.request(n);
		}
		
		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				
				inner.cancel();
				s.cancel();
			}
		}
		
		void drain() {
			if (WIP.getAndIncrement(this) == 0) {
				
				for (;;) {
					if (cancelled) {
						return;
					}
					
					if (!active) {
						
						boolean d = done;
						
						if (d && !veryEnd) {
							Throwable ex = error;
							if (ex != null) {
								ex = Exceptions.terminate(ERROR, this);
								if (ex != Exceptions.TERMINATED) {
									actual.onError(ex);
								}
								return;
							}
						}
						
						T v;
						
						try {
							v = queue.poll();
						} catch (Throwable e) {
							actual.onError(Operators.onOperatorError(s, e));
							return;
						}
						
						boolean empty = v == null;
						
						if (d && empty) {
							Throwable ex = Exceptions.terminate(ERROR, this);
							if (ex != null && ex != Exceptions.TERMINATED) {
								actual.onError(ex);
							} else {
								actual.onComplete();
							}
							return;
						}
						
						if (!empty) {
							Publisher<? extends R> p;
							
							try {
								p = mapper.apply(v);
							} catch (Throwable e) {
								actual.onError(Operators.onOperatorError(s, e, v));
								return;
							}
							
							if (p == null) {
								actual.onError(Operators.onOperatorError(s,
										new NullPointerException("The mapper returned a " + "null Publisher"),
										v));
								return;
							}
							
							if (sourceMode != SYNC) {
								int c = consumed + 1;
								if (c == limit) {
									consumed = 0;
									s.request(c);
								} else {
									consumed = c;
								}
							}
							
							if (p instanceof Callable) {
								@SuppressWarnings("unchecked")
								Callable<R> supplier = (Callable<R>) p;
								
								R vr;
								
								try {
									vr = supplier.call();
								} catch (Throwable e) {
									actual.onError(Operators.onOperatorError(s, e, v));
									return;
								}
								
								if (vr == null) {
									continue;
								}
								
								if (inner.isUnbounded()) {
									actual.onNext(vr);
									continue;
								} else {
									active = true;
									inner.set(new WeakScalarSubscription<>(vr, inner));
								}
							} else {
								active = true;
								p.subscribe(inner);
							}
						}
					}
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
				}
			}
		}
	}

	interface FluxConcatMapSupport<T> {
		
		void innerNext(T value);
		
		void innerComplete();
		
		void innerError(Throwable e);
	}
	
	static final class ConcatMapInner<R>
			extends Operators.MultiSubscriptionSubscriber<R, R> {

		final FluxConcatMapSupport<R> parent;

		long produced;

		public ConcatMapInner(FluxConcatMapSupport<R> parent) {
			super(null);
			this.parent = parent;
		}

		@Override
		public void onNext(R t) {
			produced++;
			
			parent.innerNext(t);
		}
		
		@Override
		public void onError(Throwable t) {
			long p = produced;
			
			if (p != 0L) {
				produced = 0L;
				produced(p);
			}

			parent.innerError(t);
		}
		
		@Override
		public void onComplete() {
			long p = produced;
			
			if (p != 0L) {
				produced = 0L;
				produced(p);
			}

			parent.innerComplete();
		}
	}
}
