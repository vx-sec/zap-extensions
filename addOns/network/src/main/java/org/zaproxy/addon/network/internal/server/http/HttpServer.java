/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2022 The ZAP Development Team
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
 */
package org.zaproxy.addon.network.internal.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.util.concurrent.EventExecutorGroup;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.network.HttpHeader;
import org.zaproxy.addon.network.ConnectionOptions;
import org.zaproxy.addon.network.internal.ChannelAttributes;
import org.zaproxy.addon.network.internal.TlsUtils;
import org.zaproxy.addon.network.internal.cert.ServerCertificateService;
import org.zaproxy.addon.network.internal.codec.HttpRequestDecoder;
import org.zaproxy.addon.network.internal.codec.HttpResponseEncoder;
import org.zaproxy.addon.network.internal.codec.HttpToHttp2ConnectionHandler;
import org.zaproxy.addon.network.internal.codec.InboundHttp2ToHttpAdapter;
import org.zaproxy.addon.network.internal.handlers.ConnectRequestHandler;
import org.zaproxy.addon.network.internal.handlers.ReadTimeoutHandler;
import org.zaproxy.addon.network.internal.handlers.RecursiveRequestHandler;
import org.zaproxy.addon.network.internal.handlers.ServerExceptionHandler;
import org.zaproxy.addon.network.internal.handlers.TlsConfig;
import org.zaproxy.addon.network.internal.handlers.TlsProtocolHandler;
import org.zaproxy.addon.network.internal.server.BaseServer;

/**
 * An HTTP server.
 *
 * <p>Provides the following functionality:
 *
 * <ul>
 *   <li>Read timeout;
 *   <li>Handling of HTTP/1.x CONNECT requests and TLS upgrade;
 *   <li>Handling of TLS ALPN, with support for HTTP/1.1 and HTTP/2;
 *   <li>Recursive check;
 *   <li>Exception handling;
 * </ul>
 */
public class HttpServer extends BaseServer {

    private static final String TIMEOUT_HANDLER_NAME = "timeout";

    private static final Logger LOGGER = LogManager.getLogger(HttpServer.class);

    private static final TlsConfig DEFAULT_TLS_CONFIG = new TlsConfig();

    private static final String HTTP_DECODER_HANDLER_NAME = "http.decoder";
    private static final String HTTP_ENCODER_HANDLER_NAME = "http.encoder";

    private final EventExecutorGroup mainHandlerExecutor;
    private final ServerCertificateService certificateService;
    private Supplier<MainServerHandler> handler;
    private DefaultServerConfig serverConfig;

    /**
     * Constructs a {@code HttpServer} with the given properties and no handler.
     *
     * <p>A handler must be set before starting the server.
     *
     * @param group the event loop group.
     * @param mainHandlerExecutor the event executor for the main handler.
     * @param certificateService the certificate service.
     * @see #setMainServerHandler(Supplier)
     */
    protected HttpServer(
            NioEventLoopGroup group,
            EventExecutorGroup mainHandlerExecutor,
            ServerCertificateService certificateService) {
        super(group);
        this.mainHandlerExecutor = Objects.requireNonNull(mainHandlerExecutor);
        this.certificateService = Objects.requireNonNull(certificateService);

        this.serverConfig = new DefaultServerConfig();
        setChannelInitialiser(this::initChannel);
    }

    /**
     * Constructs a {@code HttpServer} with the given properties.
     *
     * @param group the event loop group.
     * @param mainHandlerExecutor the event executor for the main handler.
     * @param certificateService the certificate service.
     * @param handler the main handler.
     */
    public HttpServer(
            NioEventLoopGroup group,
            EventExecutorGroup mainHandlerExecutor,
            ServerCertificateService certificateService,
            Supplier<MainServerHandler> handler) {
        this(group, mainHandlerExecutor, certificateService);

        setMainServerHandler(handler);
    }

    /**
     * Sets the main server handler.
     *
     * @param handler the main server handler.
     * @throws NullPointerException if the given handler is {@code null}.
     */
    protected void setMainServerHandler(Supplier<MainServerHandler> handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    protected void initChannel(SocketChannel ch) {
        ch.attr(ChannelAttributes.CERTIFICATE_SERVICE).set(certificateService);
        ch.attr(ChannelAttributes.SERVER_CONFIG).set(serverConfig);
        ch.attr(ChannelAttributes.TLS_CONFIG).set(DEFAULT_TLS_CONFIG);
        ch.attr(ChannelAttributes.PIPELINE_CONFIGURATOR).set(HttpServer::protocolConfiguration);

        ch.pipeline()
                .addLast(
                        TIMEOUT_HANDLER_NAME,
                        new ReadTimeoutHandler(ConnectionOptions.DEFAULT_TIMEOUT, TimeUnit.SECONDS))
                .addLast("tls.upgrade", new TlsProtocolHandler())
                .addLast(HTTP_DECODER_HANDLER_NAME, new HttpRequestDecoder())
                .addLast(HTTP_ENCODER_HANDLER_NAME, HttpResponseEncoder.getInstance())
                .addLast("http.connect", ConnectRequestHandler.getInstance())
                .addLast("http.recursive", RecursiveRequestHandler.getInstance())
                .addLast(mainHandlerExecutor, "http.main-handler", handler.get())
                .addLast("exception", new ServerExceptionHandler());
    }

    static void protocolConfiguration(ChannelHandlerContext ctx, String protocol) {
        switch (protocol) {
            case TlsUtils.APPLICATION_PROTOCOL_HTTP_1_1:
                // Nothing to do, HTTP/1.1 is the default protocol.
                break;

            case TlsUtils.APPLICATION_PROTOCOL_HTTP_2:
                DefaultHttp2Connection connection = new DefaultHttp2Connection(true);

                boolean tlsUpgraded =
                        Boolean.TRUE.equals(
                                ctx.channel().attr(ChannelAttributes.TLS_UPGRADED).get());
                ChannelPipeline pipeline = ctx.pipeline();
                pipeline.remove(TIMEOUT_HANDLER_NAME);
                pipeline.remove(HTTP_DECODER_HANDLER_NAME);
                pipeline.replace(
                        HTTP_ENCODER_HANDLER_NAME,
                        "http2.codec",
                        HttpToHttp2ConnectionHandler.create(
                                new InboundHttp2ToHttpAdapter(connection),
                                null,
                                connection,
                                tlsUpgraded ? HttpHeader.HTTPS : HttpHeader.HTTP));
                break;

            default:
                LOGGER.error("Negotiated protocol not supported.");
                ctx.close();
        }
    }

    @Override
    public int start(String address, int port) throws IOException {
        if (handler == null) {
            throw new IOException("No main server handler set.");
        }
        int boundPort = super.start(address, port);
        serverConfig.setAddress(address);
        return boundPort;
    }
}
