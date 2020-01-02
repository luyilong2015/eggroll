/*
 * Copyright (c) 2019 - now, Eggroll Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.webank.eggroll.core.command

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.{CompletableFuture, CountDownLatch}
import java.util.function.Supplier

import com.google.protobuf.{ByteString, UnsafeByteOperations}
import com.webank.eggroll.core.command.Command.CommandResponse
import com.webank.eggroll.core.command.CommandModelPbMessageSerdes._
import com.webank.eggroll.core.concurrent.AwaitSettableFuture
import com.webank.eggroll.core.constant.{SerdesTypes, SessionConfKeys}
import com.webank.eggroll.core.datastructure.RpcMessage
import com.webank.eggroll.core.di.Singletons
import com.webank.eggroll.core.error.DistributedRuntimeException
import com.webank.eggroll.core.factory.GrpcChannelFactory
import com.webank.eggroll.core.grpc.client.{GrpcClientContext, GrpcClientTemplate}
import com.webank.eggroll.core.grpc.observer.SameTypeFutureCallerResponseStreamObserver
import com.webank.eggroll.core.meta.ErEndpoint
import com.webank.eggroll.core.session.StaticErConf
import com.webank.eggroll.core.util.{Logging, SerdesUtils, TimeUtils}
import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

class CommandClient(defaultEndpoint:ErEndpoint = null, serdesType: String = SerdesTypes.PROTOBUF, isSecure:Boolean=false)
  extends Logging {
  // TODO:1: for java
  def this(){
    this(null, SerdesTypes.PROTOBUF, false)
  }
  def proxy[T](implicit tag:ClassTag[T]): T = {
    Proxy.newProxyInstance(this.getClass.getClassLoader,  Array[Class[_]](tag.runtimeClass), new InvocationHandler {
      override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
        call(new CommandURI(
          tag.runtimeClass.getName.replaceFirst("com.webank","/v2").replace(".","/") + "/" + method.getName),
          args.map(_.asInstanceOf[RpcMessage]):_*)(ClassTag(method.getReturnType))
      }
    }).asInstanceOf[T]
  }

  val sessionId = StaticErConf.getString(SessionConfKeys.CONFKEY_SESSION_ID)
  // TODO:1: confirm client won't exit bug of Singletons.getNoCheck(classOf[GrpcChannelFactory]).getChannel(endpoint, isSecure)
  // TODO:0: add channel args
  private def buildChannel(endpoint: ErEndpoint): ManagedChannel = {
    val builder = ManagedChannelBuilder.forAddress(endpoint.host, endpoint.port)
    builder.usePlaintext()
    builder.build()
  }

  def call[T](commandURI: CommandURI, args: RpcMessage*)(implicit tag:ClassTag[T]): T = {
    val stub = CommandServiceGrpc.newBlockingStub(buildChannel(defaultEndpoint))
    val argBytes = args.map(x => ByteString.copyFrom(SerdesUtils.rpcMessageToBytes(x, SerdesTypes.PROTOBUF)))
    val resp = stub.call(Command.CommandRequest.newBuilder
        .setId(System.currentTimeMillis + "")
        .setUri(commandURI.uri.toString)
        .addAllArgs(argBytes.asJava)
        .build)
    SerdesUtils.rpcMessageFromBytes(resp.getResults(0).toByteArray,
      tag.runtimeClass, SerdesTypes.PROTOBUF).asInstanceOf[T]
  }

  def call[T](commandURI: CommandURI, args: Array[(Array[RpcMessage], ErEndpoint)])(implicit tag:ClassTag[T]): Array[T] = {
    val futures = args.map {
      case (rpcMessages, endpoint) =>
        val ch: ManagedChannel = Singletons.getNoCheck(classOf[GrpcChannelFactory]).getChannel(endpoint, isSecure)
        val stub = CommandServiceGrpc.newFutureStub(ch)
        val argBytes = rpcMessages.map(x => UnsafeByteOperations.unsafeWrap(SerdesUtils.rpcMessageToBytes(x, SerdesTypes.PROTOBUF)))

        stub.call(
          Command.CommandRequest.newBuilder
            .setId(s"${sessionId}-command-${TimeUtils.getNowMs()}")
            .setUri(commandURI.uri.toString)
            .addAllArgs(argBytes.toList.asJava).build)

    }

    futures.map(f => SerdesUtils.rpcMessageFromBytes(f.get().getResults(0).toByteArray,
      tag.runtimeClass, SerdesTypes.PROTOBUF).asInstanceOf[T])
  }
  @Deprecated
  def simpleSyncSend[T >: RpcMessage](input: RpcMessage,
                                      outputType: Class[_],
                                      endpoint: ErEndpoint,
                                      commandURI: CommandURI,
                                      serdesType: String = SerdesTypes.PROTOBUF): T = {
    val delayedResult = new AwaitSettableFuture[CommandResponse]

    val context = new GrpcClientContext[
      CommandServiceGrpc.CommandServiceStub,
      Command.CommandRequest,
      CommandResponse]()

    context.setServerEndpoint(endpoint)
      .setCalleeStreamingMethodInvoker(
        (stub: CommandServiceGrpc.CommandServiceStub,
         request: Command.CommandRequest,
         responseObserver: StreamObserver[CommandResponse])
        => stub.call(request, responseObserver))
      .setCallerStreamObserverClassAndInitArgs(classOf[CommandResponseObserver], delayedResult)
      .setStubClass(classOf[CommandServiceGrpc.CommandServiceStub])

    val template = new GrpcClientTemplate[
      CommandServiceGrpc.CommandServiceStub,
      Command.CommandRequest,
      CommandResponse]()
      .setGrpcClientContext(context)

    val request = ErCommandRequest(
      uri = commandURI.uri.toString, args = Array(SerdesUtils.rpcMessageToBytes(input)))

    val response = template.calleeStreamingRpcWithImmediateDelayedResult(
      request.toProto(), delayedResult)

    val erResponse = response.fromProto()
    val byteResult = erResponse.results(0)

    if (byteResult.length != 0)
      SerdesUtils.rpcMessageFromBytes(bytes = byteResult, targetType = outputType, serdesTypes = serdesType)
    else
      null
  }
  class CommandCallSupplier[T](endpoint: ErEndpoint, isSecure: Boolean, commandURI: CommandURI, args: RpcMessage*)(implicit tag:ClassTag[T]) extends Supplier[T] {
    override def get(): T = {
      val ch: ManagedChannel = Singletons.getNoCheck(classOf[GrpcChannelFactory]).getChannel(endpoint, isSecure)
      val stub: CommandServiceGrpc.CommandServiceBlockingStub = CommandServiceGrpc.newBlockingStub(ch)
      val argBytes = args.map(x => UnsafeByteOperations.unsafeWrap(SerdesUtils.rpcMessageToBytes(x, SerdesTypes.PROTOBUF)))

      val resp: Command.CommandResponse = stub.call(
        Command.CommandRequest.newBuilder
          .setId(s"${sessionId}-command-${TimeUtils.getNowMs()}")
          .setUri(commandURI.uri.toString)
          .addAllArgs(argBytes.asJava).build)
      SerdesUtils.rpcMessageFromBytes(resp.getResults(0).toByteArray,
        tag.runtimeClass, SerdesTypes.PROTOBUF).asInstanceOf[T]
    }
  }
}



class CommandResponseObserver(finishLatch: CountDownLatch, asFuture: AwaitSettableFuture[CommandResponse])
  extends SameTypeFutureCallerResponseStreamObserver[Command.CommandRequest, CommandResponse](finishLatch, asFuture) {
}