/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.hadooprpc.client;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.ratis.client.impl.ClientProtoUtils;
import org.apache.ratis.hadooprpc.Proxy;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.ReinitializeRequest;
import org.apache.ratis.protocol.ServerInformatonRequest;
import org.apache.ratis.protocol.ServerInformationReply;
import org.apache.ratis.protocol.SetConfigurationRequest;
import org.apache.ratis.shaded.com.google.protobuf.ServiceException;
import org.apache.ratis.util.CheckedFunction;
import org.apache.ratis.util.ProtoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Function;


@InterfaceAudience.Private
public class CombinedClientProtocolClientSideTranslatorPB
    extends Proxy<CombinedClientProtocolPB>
    implements CombinedClientProtocol {
  private static final Logger LOG = LoggerFactory.getLogger(CombinedClientProtocolClientSideTranslatorPB.class);

  public CombinedClientProtocolClientSideTranslatorPB(
      String addressStr, Configuration conf) throws IOException {
    super(CombinedClientProtocolPB.class, addressStr, conf);
  }

  @Override
  public RaftClientReply submitClientRequest(RaftClientRequest request)
      throws IOException {
    return handleRequest(request,
        ClientProtoUtils::toRaftClientRequestProto,
        ClientProtoUtils::toRaftClientReply,
        p -> getProtocol().submitClientRequest(null, p));
  }

  @Override
  public RaftClientReply setConfiguration(SetConfigurationRequest request)
      throws IOException {
    return handleRequest(request,
        ClientProtoUtils::toSetConfigurationRequestProto,
        ClientProtoUtils::toRaftClientReply,
        p -> getProtocol().setConfiguration(null, p));
  }

  @Override
  public RaftClientReply reinitialize(ReinitializeRequest request) throws IOException {
    return handleRequest(request,
        ClientProtoUtils::toReinitializeRequestProto,
        ClientProtoUtils::toRaftClientReply,
        p -> getProtocol().reinitialize(null, p));
  }

  @Override
  public ServerInformationReply getInfo(ServerInformatonRequest request) throws IOException {
    return handleRequest(request,
        ClientProtoUtils::toServerInformationRequestProto,
        ClientProtoUtils::toServerInformationReply,
        p -> getProtocol().serverInformation(null, p));
  }

  static <REQUEST extends RaftClientRequest, REPLY extends RaftClientReply,
      PROTO_REQ, PROTO_REP> REPLY handleRequest(
      REQUEST request,
      Function<REQUEST, PROTO_REQ> reqToProto,
      Function<PROTO_REP, REPLY> repToProto,
      CheckedFunction<PROTO_REQ, PROTO_REP, ServiceException> handler)
      throws IOException {
    final PROTO_REQ proto = reqToProto.apply(request);
    try {
      final PROTO_REP reply = handler.apply(proto);
      return repToProto.apply(reply);
    } catch (ServiceException se) {
      LOG.trace("Failed to handle " + request, se);
      throw ProtoUtils.toIOException(se);
    }
  }
}
