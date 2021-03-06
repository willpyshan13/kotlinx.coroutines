/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.rx2

import io.reactivex.*
import io.reactivex.disposables.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.*

/**
 * Converts this job to the hot reactive completable that signals
 * with [onCompleted][CompletableSubscriber.onCompleted] when the corresponding job completes.
 *
 * Every subscriber gets the signal at the same time.
 * Unsubscribing from the resulting completable **does not** affect the original job in any way.
 *
 * **Note: This is an experimental api.** Conversion of coroutines primitives to reactive entities may change
 *    in the future to account for the concept of structured concurrency.
 *
 * @param context -- the coroutine context from which the resulting completable is going to be signalled
 */
@ExperimentalCoroutinesApi
public fun Job.asCompletable(context: CoroutineContext): Completable = rxCompletable(context) {
    this@asCompletable.join()
}

/**
 * Converts this deferred value to the hot reactive maybe that signals
 * [onComplete][MaybeEmitter.onComplete], [onSuccess][MaybeEmitter.onSuccess] or [onError][MaybeEmitter.onError].
 *
 * Every subscriber gets the same completion value.
 * Unsubscribing from the resulting maybe **does not** affect the original deferred value in any way.
 *
 * **Note: This is an experimental api.** Conversion of coroutines primitives to reactive entities may change
 *    in the future to account for the concept of structured concurrency.
 *
 * @param context -- the coroutine context from which the resulting maybe is going to be signalled
 */
@ExperimentalCoroutinesApi
public fun <T> Deferred<T?>.asMaybe(context: CoroutineContext): Maybe<T> = rxMaybe(context) {
    this@asMaybe.await()
}

/**
 * Converts this deferred value to the hot reactive single that signals either
 * [onSuccess][SingleSubscriber.onSuccess] or [onError][SingleSubscriber.onError].
 *
 * Every subscriber gets the same completion value.
 * Unsubscribing from the resulting single **does not** affect the original deferred value in any way.
 *
 * **Note: This is an experimental api.** Conversion of coroutines primitives to reactive entities may change
 *    in the future to account for the concept of structured concurrency.
 *
 * @param context -- the coroutine context from which the resulting single is going to be signalled
 */
@ExperimentalCoroutinesApi
public fun <T : Any> Deferred<T>.asSingle(context: CoroutineContext): Single<T> = rxSingle(context) {
    this@asSingle.await()
}

/**
 * Converts a stream of elements received from the channel to the hot reactive observable.
 *
 * Every subscriber receives values from this channel in **fan-out** fashion. If the are multiple subscribers,
 * they'll receive values in round-robin way.
 */
@Deprecated(
    message = "Deprecated in the favour of Flow",
    level = DeprecationLevel.WARNING, replaceWith = ReplaceWith("this.consumeAsFlow().asObservable()")
)
public fun <T : Any> ReceiveChannel<T>.asObservable(context: CoroutineContext): Observable<T> = rxObservable(context) {
    for (t in this@asObservable)
        send(t)
}

/**
 * Transforms given cold [ObservableSource] into cold [Flow].
 *
 * The resulting flow is _cold_, which means that [ObservableSource.subscribe] is called every time a terminal operator
 * is applied to the resulting flow.
 *
 * A channel with the [default][Channel.BUFFERED] buffer size is used. Use the [buffer] operator on the
 * resulting flow to specify a user-defined value and to control what happens when data is produced faster
 * than consumed, i.e. to control the back-pressure behavior. Check [callbackFlow] for more details.
 */
@ExperimentalCoroutinesApi
public fun <T: Any> ObservableSource<T>.asFlow(): Flow<T> = callbackFlow {
    val disposableRef = AtomicReference<Disposable>()
    val observer = object : Observer<T> {
        override fun onComplete() { close() }
        override fun onSubscribe(d: Disposable) { if (!disposableRef.compareAndSet(null, d)) d.dispose() }
        override fun onNext(t: T) { sendBlocking(t) }
        override fun onError(e: Throwable) { close(e) }
    }

    subscribe(observer)
    awaitClose { disposableRef.getAndSet(Disposables.disposed())?.dispose() }
}

/**
 * Converts the given flow to a cold observable.
 * The original flow is cancelled when the observable subscriber is disposed.
 */
@JvmName("from")
@ExperimentalCoroutinesApi
public fun <T: Any> Flow<T>.asObservable() : Observable<T> = Observable.create { emitter ->
    /*
     * ATOMIC is used here to provide stable behaviour of subscribe+dispose pair even if
     * asObservable is already invoked from unconfined
     */
    val job = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.ATOMIC) {
        try {
            collect { value -> emitter.onNext(value) }
            emitter.onComplete()
        } catch (e: Throwable) {
            // 'create' provides safe emitter, so we can unconditionally call on* here if exception occurs in `onComplete`
            if (e !is CancellationException) {
                if (!emitter.tryOnError(e)) {
                    handleUndeliverableException(e, coroutineContext)
                }
            } else {
                emitter.onComplete()
            }
        }
    }
    emitter.setCancellable(RxCancellable(job))
}

/**
 * Converts the given flow to a cold flowable.
 * The original flow is cancelled when the flowable subscriber is disposed.
 */
@JvmName("from")
@ExperimentalCoroutinesApi
public fun <T: Any> Flow<T>.asFlowable(): Flowable<T> = Flowable.fromPublisher(asPublisher())
