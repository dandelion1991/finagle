package com.twitter.finagle.netty4.channel

import com.twitter.finagle.CancelledConnectionException
import io.netty.channel._
import io.netty.util.concurrent.{Future => NettyFuture, GenericFutureListener}

/**
 * This trait provides [[GenericFutureListener]]s that are useful for implementing handlers
 * that delay a connect-promise until some additional step is done (i.e., HTTP proxy connect,
 * SSL handshake, etc).
 *
 * For example, this is used by:
 *
 * - [[com.twitter.finagle.netty4.ssl.SslConnectHandler]]
 * - [[com.twitter.finagle.netty4.proxy.HttpProxyConnectHandler]]
 * - [[com.twitter.finagle.netty4.proxy.SocksProxyConnectHandler]]
 */
private[netty4] trait ConnectPromiseDelayListeners { self: BufferingChannelOutboundHandler =>

  /**
   * Creates a new [[GenericFutureListener]] that cancels a given `promise` when the
   * [[NettyFuture]] it's listening on is cancelled.
   *
   * @note The future listener returned from this method also fails pending writes and closes
   *       the channel if the `promise` is already satisfied.
   */
  def proxyCancellationsTo(
    promise: ChannelPromise,
    ctx: ChannelHandlerContext
  ): GenericFutureListener[NettyFuture[Any]] = new GenericFutureListener[NettyFuture[Any]] {
    override def operationComplete(f: NettyFuture[Any]): Unit =
      if (f.isCancelled) {
        if (!promise.cancel(true) && promise.isSuccess) {
          // New connect promise wasn't cancelled because it was already satisfied (connected) so
          // we need to close the channel to prevent resource leaks.
          // See https://github.com/twitter/finagle/issues/345
          failPendingWrites(ctx, new CancelledConnectionException())
          ctx.close()
        }
      }
  }

  /**
   * Creates a new [[GenericFutureListener]] that fails a given `promise` when the
   * [[NettyFuture]] it's listening on is failed.
   */
  def proxyFailuresTo(
    promise: ChannelPromise
  ): GenericFutureListener[NettyFuture[Any]] = new GenericFutureListener[NettyFuture[Any]] {
    override def operationComplete(f: NettyFuture[Any]): Unit =
    // We filter cancellation here since we assume it was proxied from the old promise and
    // is already being handled in `cancelPromiseWhenCancelled`.
    if (!f.isSuccess && !f.isCancelled) {
      // The connect request was failed so the channel was never active. Since no writes are
      // expected from the previous handler, no need to fail the pending writes.
      promise.setFailure(f.cause)
    }
  }
}