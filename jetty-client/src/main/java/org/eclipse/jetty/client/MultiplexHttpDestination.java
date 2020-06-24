//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import org.eclipse.jetty.util.annotation.ManagedAttribute;

/**
 * <p>A destination for those transports that are multiplex (e.g. HTTP/2).</p>
 * <p>Transports that negotiate the protocol, and therefore do not know in advance
 * whether they are duplex or multiplex, should use this class and when the
 * cardinality is known call {@link #setMaxRequestsPerConnection(int)} with
 * the proper cardinality.</p>
 * <p>If the cardinality is {@code 1}, the behavior of this class is similar
 * to that of {@link DuplexHttpDestination}.</p>
 */
public class MultiplexHttpDestination extends HttpDestination implements HttpDestination.Multiplexed
{
    public MultiplexHttpDestination(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    @ManagedAttribute(value = "The maximum number of concurrent requests per connection")
    public int getMaxRequestsPerConnection()
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof ConnectionPool.Multiplexable)
            return ((ConnectionPool.Multiplexable)connectionPool).getMaxMultiplex();
        return 1;
    }

    public void setMaxRequestsPerConnection(int maxRequestsPerConnection)
    {
        ConnectionPool connectionPool = getConnectionPool();
        if (connectionPool instanceof ConnectionPool.Multiplexable)
            ((ConnectionPool.Multiplexable)connectionPool).setMaxMultiplex(maxRequestsPerConnection);
    }
}
