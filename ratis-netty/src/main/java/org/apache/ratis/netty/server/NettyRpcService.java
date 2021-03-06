/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.ratis.netty.server;

import org.apache.ratis.client.impl.ClientProtoUtils;
import org.apache.ratis.netty.NettyConfigKeys;
import org.apache.ratis.netty.NettyRpcProxy;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.ServerInformationReply;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.shaded.io.netty.bootstrap.ServerBootstrap;
import org.apache.ratis.shaded.io.netty.channel.*;
import org.apache.ratis.shaded.io.netty.channel.nio.NioEventLoopGroup;
import org.apache.ratis.shaded.io.netty.channel.socket.SocketChannel;
import org.apache.ratis.shaded.io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.ratis.shaded.io.netty.handler.codec.protobuf.ProtobufDecoder;
import org.apache.ratis.shaded.io.netty.handler.codec.protobuf.ProtobufEncoder;
import org.apache.ratis.shaded.io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import org.apache.ratis.shaded.io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import org.apache.ratis.shaded.io.netty.handler.logging.LogLevel;
import org.apache.ratis.shaded.io.netty.handler.logging.LoggingHandler;
import org.apache.ratis.shaded.proto.RaftProtos.*;
import org.apache.ratis.shaded.proto.netty.NettyProtos.RaftNettyExceptionReplyProto;
import org.apache.ratis.shaded.proto.netty.NettyProtos.RaftNettyServerReplyProto;
import org.apache.ratis.shaded.proto.netty.NettyProtos.RaftNettyServerRequestProto;
import org.apache.ratis.util.CodeInjectionForTesting;
import org.apache.ratis.util.LifeCycle;
import org.apache.ratis.util.ProtoUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/**
 * A netty server endpoint that acts as the communication layer.
 */
public final class NettyRpcService implements RaftServerRpc {
  static final String CLASS_NAME = NettyRpcService.class.getSimpleName();
  public static final String SEND_SERVER_REQUEST = CLASS_NAME + ".sendServerRequest";

  public static class Builder extends RaftServerRpc.Builder<Builder, NettyRpcService> {
    private Builder() {}

    @Override
    public Builder getThis() {
      return this;
    }

    @Override
    public NettyRpcService build() {
      return new NettyRpcService(getServer());
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  private final LifeCycle lifeCycle = new LifeCycle(getClass().getSimpleName());
  private final RaftServer server;
  private final RaftPeerId id;

  private final EventLoopGroup bossGroup = new NioEventLoopGroup();
  private final EventLoopGroup workerGroup = new NioEventLoopGroup();
  private final ChannelFuture channelFuture;

  private final NettyRpcProxy.PeerMap proxies = new NettyRpcProxy.PeerMap();

  @ChannelHandler.Sharable
  class InboundHandler extends SimpleChannelInboundHandler<RaftNettyServerRequestProto> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RaftNettyServerRequestProto proto) {
      final RaftNettyServerReplyProto reply = handle(proto);
      ctx.writeAndFlush(reply);
    }
  }

  /** Constructs a netty server with the given port. */
  private NettyRpcService(RaftServer server) {
    this.server = server;
    this.id = server.getId();
    this.proxies.setName(id.toString());

    final ChannelInitializer<SocketChannel> initializer
        = new ChannelInitializer<SocketChannel>() {
      @Override
      protected void initChannel(SocketChannel ch) throws Exception {
        final ChannelPipeline p = ch.pipeline();

        p.addLast(new ProtobufVarint32FrameDecoder());
        p.addLast(new ProtobufDecoder(RaftNettyServerRequestProto.getDefaultInstance()));
        p.addLast(new ProtobufVarint32LengthFieldPrepender());
        p.addLast(new ProtobufEncoder());

        p.addLast(new InboundHandler());
      }
    };

    final int port = NettyConfigKeys.Server.port(server.getProperties());
    channelFuture = new ServerBootstrap()
        .group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .handler(new LoggingHandler(LogLevel.INFO))
        .childHandler(initializer)
        .bind(port);
  }

  @Override
  public SupportedRpcType getRpcType() {
    return SupportedRpcType.NETTY;
  }

  private Channel getChannel() {
    return channelFuture.awaitUninterruptibly().channel();
  }

  @Override
  public void start() {
    lifeCycle.startAndTransition(() -> channelFuture.syncUninterruptibly());
  }

  @Override
  public void close() {
    lifeCycle.checkStateAndClose(() -> {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
      final ChannelFuture f = getChannel().close();
      proxies.close();
      f.syncUninterruptibly();
    });
  }

  @Override
  public InetSocketAddress getInetSocketAddress() {
    return (InetSocketAddress)getChannel().localAddress();
  }

  RaftNettyServerReplyProto handle(RaftNettyServerRequestProto proto) {
    RaftRpcRequestProto rpcRequest = null;
    try {
      switch (proto.getRaftNettyServerRequestCase()) {
        case REQUESTVOTEREQUEST: {
          final RequestVoteRequestProto request = proto.getRequestVoteRequest();
          rpcRequest = request.getServerRequest();
          final RequestVoteReplyProto reply = server.requestVote(request);
          return RaftNettyServerReplyProto.newBuilder()
              .setRequestVoteReply(reply)
              .build();
        }
        case APPENDENTRIESREQUEST: {
          final AppendEntriesRequestProto request = proto.getAppendEntriesRequest();
          rpcRequest = request.getServerRequest();
          final AppendEntriesReplyProto reply = server.appendEntries(request);
          return RaftNettyServerReplyProto.newBuilder()
              .setAppendEntriesReply(reply)
              .build();
        }
        case INSTALLSNAPSHOTREQUEST: {
          final InstallSnapshotRequestProto request = proto.getInstallSnapshotRequest();
          rpcRequest = request.getServerRequest();
          final InstallSnapshotReplyProto reply = server.installSnapshot(request);
          return RaftNettyServerReplyProto.newBuilder()
              .setInstallSnapshotReply(reply)
              .build();
        }
        case RAFTCLIENTREQUEST: {
          final RaftClientRequestProto request = proto.getRaftClientRequest();
          rpcRequest = request.getRpcRequest();
          final RaftClientReply reply = server.submitClientRequest(
              ClientProtoUtils.toRaftClientRequest(request));
          return RaftNettyServerReplyProto.newBuilder()
              .setRaftClientReply(ClientProtoUtils.toRaftClientReplyProto(reply))
              .build();
        }
        case SETCONFIGURATIONREQUEST: {
          final SetConfigurationRequestProto request = proto.getSetConfigurationRequest();
          rpcRequest = request.getRpcRequest();
          final RaftClientReply reply = server.setConfiguration(
              ClientProtoUtils.toSetConfigurationRequest(request));
          return RaftNettyServerReplyProto.newBuilder()
              .setRaftClientReply(ClientProtoUtils.toRaftClientReplyProto(reply))
              .build();
        }
        case REINITIALIZEREQUEST: {
          final ReinitializeRequestProto request = proto.getReinitializeRequest();
          rpcRequest = request.getRpcRequest();
          final RaftClientReply reply = server.reinitialize(
              ClientProtoUtils.toReinitializeRequest(request));
          return RaftNettyServerReplyProto.newBuilder()
              .setRaftClientReply(ClientProtoUtils.toRaftClientReplyProto(reply))
              .build();
        }
        case SERVERINFORMATIONREQUEST: {
          final ServerInformationRequestProto request = proto.getServerInformationRequest();
          rpcRequest = request.getRpcRequest();
          final ServerInformationReply reply = server.getInfo(
              ClientProtoUtils.toServerInformationRequest(request));
          return RaftNettyServerReplyProto.newBuilder()
              .setServerInfoReply(ClientProtoUtils.toServerInformationReplyProto(reply))
              .build();
        }
        case RAFTNETTYSERVERREQUEST_NOT_SET:
          throw new IllegalArgumentException("Request case not set in proto: "
              + proto.getRaftNettyServerRequestCase());
        default:
          throw new UnsupportedOperationException("Request case not supported: "
              + proto.getRaftNettyServerRequestCase());
      }
    } catch (IOException ioe) {
      return toRaftNettyServerReplyProto(
          Objects.requireNonNull(rpcRequest, "rpcRequest = null"), ioe);
    }
  }

  private static RaftNettyServerReplyProto toRaftNettyServerReplyProto(
      RaftRpcRequestProto request, IOException e) {
    final RaftRpcReplyProto.Builder rpcReply = RaftRpcReplyProto.newBuilder()
        .setRequestorId(request.getRequestorId())
        .setReplyId(request.getReplyId())
        .setCallId(request.getCallId())
        .setSuccess(false);
    final RaftNettyExceptionReplyProto.Builder ioe = RaftNettyExceptionReplyProto.newBuilder()
        .setRpcReply(rpcReply)
        .setException(ProtoUtils.toByteString(e));
    return RaftNettyServerReplyProto.newBuilder().setExceptionReply(ioe).build();
  }

  @Override
  public RequestVoteReplyProto requestVote(RequestVoteRequestProto request) throws IOException {
    CodeInjectionForTesting.execute(SEND_SERVER_REQUEST, id, null, request);

    final RaftNettyServerRequestProto proto = RaftNettyServerRequestProto.newBuilder()
        .setRequestVoteRequest(request)
        .build();
    final RaftRpcRequestProto serverRequest = request.getServerRequest();
    return sendRaftNettyServerRequestProto(serverRequest, proto).getRequestVoteReply();
  }

  @Override
  public AppendEntriesReplyProto appendEntries(AppendEntriesRequestProto request) throws IOException {
    CodeInjectionForTesting.execute(SEND_SERVER_REQUEST, id, null, request);

    final RaftNettyServerRequestProto proto = RaftNettyServerRequestProto.newBuilder()
        .setAppendEntriesRequest(request)
        .build();
    final RaftRpcRequestProto serverRequest = request.getServerRequest();
    return sendRaftNettyServerRequestProto(serverRequest, proto).getAppendEntriesReply();
  }

  @Override
  public InstallSnapshotReplyProto installSnapshot(InstallSnapshotRequestProto request) throws IOException {
    CodeInjectionForTesting.execute(SEND_SERVER_REQUEST, id, null, request);

    final RaftNettyServerRequestProto proto = RaftNettyServerRequestProto.newBuilder()
        .setInstallSnapshotRequest(request)
        .build();
    final RaftRpcRequestProto serverRequest = request.getServerRequest();
    return sendRaftNettyServerRequestProto(serverRequest, proto).getInstallSnapshotReply();
  }

  private RaftNettyServerReplyProto sendRaftNettyServerRequestProto(
      RaftRpcRequestProto request, RaftNettyServerRequestProto proto)
      throws IOException {
    final RaftPeerId id = RaftPeerId.valueOf(request.getReplyId());
    final NettyRpcProxy p = proxies.getProxy(id);
    try {
      return p.send(request, proto);
    } catch (ClosedChannelException cce) {
      proxies.resetProxy(id);
      throw cce;
    }
  }

  @Override
  public void addPeers(Iterable<RaftPeer> peers) {
    proxies.addPeers(peers);
  }
}
