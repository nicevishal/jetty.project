//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketStatsTest
{

    @WebSocket
    public static class ClientSocket
    {
        CountDownLatch closed = new CountDownLatch(1);

        String behavior;

        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            behavior = session.getPolicy().getBehavior().name();
            System.err.println(toString() + " Socket Connected: " + session);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            System.err.println(toString() + " Socket Closed: " + statusCode + ":" + reason);
            closed.countDown();
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }

        @Override
        public String toString()
        {
            return String.format("[%s@%s]", behavior, Integer.toHexString(hashCode()));
        }
    }

    @WebSocket
    public static class EchoSocket extends ClientSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
            session.getRemote().sendString(message, WriteCallback.NOOP);
        }
    }

    public static class MyWebSocketServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.setAutoFragment(false);
            factory.addMapping("/",(req, resp)->new EchoSocket());
        }
    }

    Server server;
    WebSocketClient client;
    ConnectionStatistics statistics;
    CountDownLatch wsUpgradeComplete = new CountDownLatch(1);
    CountDownLatch wsConnectionClosed = new CountDownLatch(1);

    @BeforeEach
    public void start() throws Exception
    {
        statistics = new ConnectionStatistics()
        {
            @Override
            public void onClosed(Connection connection)
            {
                super.onClosed(connection);

                if (connection instanceof WebSocketConnection)
                    wsConnectionClosed.countDown();
                else if (connection instanceof HttpConnection)
                    wsUpgradeComplete.countDown();
            }
        };

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        connector.addBean(statistics);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(MyWebSocketServlet.class, "/testPath");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configureContext(contextHandler);
        client = new WebSocketClient();

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    long getFrameByteSize(Frame frame)
    {
        ByteBufferPool bufferPool = new MappedByteBufferPool();
        Generator generator = new Generator(bufferPool);
        ByteBuffer buffer = bufferPool.acquire(frame.getPayloadLength()+10, true);
        int pos = BufferUtil.flipToFill(buffer);
        generator.generateWholeFrame(frame, buffer);
        return buffer.position() - pos;
    }


    @Test
    public void echoStatsTest() throws Exception
    {
        URI uri = URI.create("ws://localhost:8080/testPath");
        ClientSocket socket = new ClientSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);

        final long numMessages = 10000;
        final String msgText = "hello world";

        long upgradeSentBytes;
        long upgradeReceivedBytes;

        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            wsUpgradeComplete.await(5, TimeUnit.SECONDS);
            upgradeSentBytes = statistics.getSentBytes();
            upgradeReceivedBytes = statistics.getReceivedBytes();

            for (int i=0; i<numMessages; i++)
                session.getRemote().sendString(msgText);
        }
        assertTrue(socket.closed.await(5, TimeUnit.SECONDS));
        assertTrue(wsConnectionClosed.await(5, TimeUnit.SECONDS));

        assertThat(statistics.getConnectionsMax(), is(1L));
        assertThat(statistics.getConnections(), is(0L));

        assertThat(statistics.getSentMessages(), is(numMessages + 2L));
        assertThat(statistics.getReceivedMessages(), is(numMessages + 2L));

        Frame textFrame = new Frame(OpCode.TEXT, msgText);
        Frame closeFrame = new Frame(OpCode.CLOSE);

        final long textFrameSize = getFrameByteSize(textFrame);
        final long closeFrameSize = getFrameByteSize(closeFrame);
        final int maskSize = 4; // We use 4 byte mask for client frames in WSConnection

        final long expectedSent = upgradeSentBytes + numMessages*textFrameSize + closeFrameSize;
        final long expectedReceived = upgradeReceivedBytes + numMessages*(textFrameSize+maskSize) + closeFrameSize+maskSize;

        assertThat(statistics.getSentBytes(), is(expectedSent));
        assertThat(statistics.getReceivedBytes(), is(expectedReceived));
    }
}
