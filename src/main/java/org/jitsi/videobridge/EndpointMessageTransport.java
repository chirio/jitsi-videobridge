/*
 * Copyright @ 2017 Atlassian Pty Ltd
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
package org.jitsi.videobridge;

import org.jitsi.eventadmin.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.rest.*;
import org.json.simple.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. Supports two underlying transport mechanisms --
 * WebRTC data channels and {@code WebSocket}s.
 *
 * @author Boris Grozev
 */
class EndpointMessageTransport
    extends AbstractEndpointMessageTransport
    implements DataChannelStack.DataChannelMessageListener
{
    /**
     * The {@link Endpoint} associated with this
     * {@link EndpointMessageTransport}.
     *
     */
    private final Endpoint endpoint;

    /**
     * The last accepted web-socket by this instance, if any.
     */
    private ColibriWebSocket webSocket;

    /**
     * User to synchronize access to {@link #webSocket}
     */
    private final Object webSocketSyncRoot = new Object();

    /**
     * Whether the last active transport channel (i.e. the last to receive a
     * message from the remote endpoint) was the web socket (if {@code true}),
     * or the WebRTC data channel (if {@code false}).
     */
    private boolean webSocketLastActive = false;

    private WeakReference<DataChannel> dataChannel = new WeakReference<>(null);

    /**
     * Initializes a new {@link EndpointMessageTransport} instance.
     * @param endpoint the associated {@link Endpoint}.
     */
    EndpointMessageTransport(Endpoint endpoint, Logger parentLogger)
    {
        super(endpoint, parentLogger);
        this.endpoint = endpoint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void notifyTransportChannelConnected()
    {
        EventAdmin eventAdmin = endpoint.getConference().getEventAdmin();

        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                EventFactory.endpointMessageTransportReady(endpoint));
        }

        endpoint.getConference().endpointMessageTransportConnected(endpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onClientHello(Object src, JSONObject jsonObject)
    {
        // ClientHello was introduced for functional testing purposes. It
        // triggers a ServerHello response from Videobridge. The exchange
        // reveals (to the client) that the transport channel between the
        // remote endpoint and the Videobridge is operational.
        // We take care to send the reply using the same transport channel on
        // which we received the request..
        sendMessage(src, createServerHelloEvent(), "response to ClientHello");
    }

    /**
     * Sends a string via a particular transport channel.
     * @param dst the transport channel.
     * @param message the message to send.
     */
    private void sendMessage(Object dst, String message)
    {
        sendMessage(dst, message, "");
    }

    /**
     * Sends a string via a particular transport channel.
     * @param dst the transport channel.
     * @param message the message to send.
     * @param errorMessage an error message to be logged in case of failure.
     */
    private void sendMessage(Object dst, String message, String errorMessage)
    {
        if (dst instanceof ColibriWebSocket)
        {
            sendMessage((ColibriWebSocket) dst, message, errorMessage);
        }
        else if (dst instanceof DataChannel)
        {
            sendMessage((DataChannel)dst, message, errorMessage);
        }
        else
        {
            throw new IllegalArgumentException("unknown transport:" + dst);
        }
    }

    /**
     * Sends a string via a particular {@link DataChannel}.
     * @param dst the data channel to send through.
     * @param message the message to send.
     * @param errorMessage an error message to be logged in case of failure.
     */
    private void sendMessage(DataChannel dst, String message, String errorMessage)
    {
        dst.sendString(message);
        endpoint.getConference().getVideobridge().getStatistics()
                .totalDataChannelMessagesSent.incrementAndGet();
    }

    /**
     * Sends a string via a particular {@link ColibriWebSocket} instance.
     * @param dst the {@link ColibriWebSocket} through which to send the message.
     * @param message the message to send.
     * @param errorMessage an error message to be logged in case of failure.
     */
    private void sendMessage(
        ColibriWebSocket dst, String message, String errorMessage)
    {
        // We'll use the async version of sendString since this may be called
        // from multiple threads.  It's just fire-and-forget though, so we
        // don't wait on the result
        dst.getRemote().sendStringByFuture(message);
        endpoint.getConference().getVideobridge().getStatistics()
            .totalColibriWebSocketMessagesSent.incrementAndGet();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected void onPinnedEndpointsChangedEvent(
        JSONObject jsonObject, Set<String> newPinnedEndpoints)
    {
        endpoint.pinnedEndpointsChanged(newPinnedEndpoints);
        propagateJSONObject(jsonObject);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSelectedEndpointsChangedEvent(
        JSONObject jsonObject, Set<String> newSelectedEndpoints)
    {
        endpoint.selectedEndpointsChanged(newSelectedEndpoints);
        propagateJSONObject(jsonObject);
    }

    /**
     * Propagates the specified JSON object to all proxies (i.e. octo endpoints) of
     * {@link #endpoint} to all remote bridges.
     *
     * @param jsonObject the JSON object to propagate.
     */
    private void propagateJSONObject(JSONObject jsonObject)
    {
        Conference conference = getConference();
        if (conference == null || conference.isExpired())
        {
            logger.warn(
                "Unable to propagate a JSON object, the conference is null or expired.");
            return;
        }

        jsonObject.put(PROP_TARGET_OCTO_ENDPOINT_ID, endpoint.getID());

        // Notify Cthulhu and its minions about selected/pinned events.
        conference.sendMessage(jsonObject.toString(), Collections.EMPTY_LIST, true);
    }

    @Override
    public void onDataChannelMessage(DataChannelMessage dataChannelMessage)
    {
        webSocketLastActive = false;
        endpoint.getConference().getVideobridge().getStatistics().
                totalDataChannelMessagesReceived.incrementAndGet();

        if (dataChannelMessage instanceof DataChannelStringMessage)
        {
            DataChannelStringMessage dataChannelStringMessage =
                    (DataChannelStringMessage)dataChannelMessage;
            onMessage(dataChannel.get(), dataChannelStringMessage.data);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void sendMessage(String msg)
        throws IOException
    {
        Object dst = getActiveTransportChannel();
        if (dst == null)
        {
            logger.info("No available transport channel, can't send a message");
        }
        else
        {
            sendMessage(dst, msg);
        }
    }

    /**
     * @return the active transport channel for this
     * {@link EndpointMessageTransport} (either the {@link #webSocket}, or
     * the WebRTC data channel represented by a {@link DataChannel}).
     * </p>
     * The "active" channel is determined based on what channels are available,
     * and which one was the last to receive data. That is, if only one channel
     * is available, it will be returned. If two channels are available, the
     * last one to have received data will be returned. Otherwise, {@code null}
     * will be returned.
     */
    //TODO(brian): seems like it'd be nice to have the websocket and datachannel
    // share a common parent class (or, at least, have a class that is returned
    // here and provides a common API but can wrap either a websocket or
    // datachannel)
    private Object getActiveTransportChannel()
    {
        DataChannel dataChannel = this.dataChannel.get();
        ColibriWebSocket webSocket = this.webSocket;

        Object dst = null;
        if (webSocketLastActive)
        {
            dst = webSocket;
        }

        // Either the socket was not the last active channel,
        // or it has been closed.
        if (dst == null)
        {
            if (dataChannel != null && dataChannel.isReady())
            {
                dst = dataChannel;
            }
        }

        // Maybe the WebRTC data channel is the last active, but it is not
        // currently available. If so, and a web-socket is available -- use it.
        if (dst == null && webSocket != null)
        {
            dst = webSocket;
        }

        return dst;
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a specific
     * {@link ColibriWebSocket} instance associated with its {@link Endpoint}
     * has connected.
     * @param ws the {@link ColibriWebSocket} which has connected.
     */
    void onWebSocketConnect(ColibriWebSocket ws)
    {
        synchronized (webSocketSyncRoot)
        {
            // If we already have a web-socket, discard it and use the new one.
            if (webSocket != null)
            {
                webSocket.getSession().close(200, "replaced");
            }

            webSocket = ws;
            webSocketLastActive = true;
            sendMessage(ws, createServerHelloEvent(), "initial ServerHello");
        }

        notifyTransportChannelConnected();
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a specific
     * {@link ColibriWebSocket} instance associated with its {@link Endpoint}
     * has been closed.
     * @param ws the {@link ColibriWebSocket} which has been closed.
     */
    public void onWebSocketClose(
            ColibriWebSocket ws, int statusCode, String reason)
    {
        synchronized (webSocketSyncRoot)
        {
            if (ws != null && ws.equals(webSocket))
            {
                webSocket = null;
                webSocketLastActive = false;
                if (logger.isDebugEnabled())
                {
                    logger.debug("Web socket closed, statusCode " + statusCode
                            + " ( " + reason + ").");
                }
            }
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void close()
    {
        synchronized (webSocketSyncRoot)
        {
            if (webSocket != null)
            {
                // 410 Gone indicates that the resource requested is no longer
                // available and will not be available again.
                webSocket.getSession().close(410, "replaced");
                webSocket = null;
                if (logger.isDebugEnabled())
                {
                    logger.debug("Endpoint expired, closed colibri web-socket.");
                }
            }
        }
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a message has been
     * received from a specific {@link ColibriWebSocket} instance associated
     * with its {@link Endpoint}.
     * @param ws the {@link ColibriWebSocket} from which a message was received.
     */
    public void onWebSocketText(ColibriWebSocket ws, String message)
    {
        if (ws == null || !ws.equals(webSocket))
        {
            logger.warn("Received text from an unknown web socket.");
            return;
        }

        endpoint.getConference().getVideobridge().getStatistics().
            totalColibriWebSocketMessagesReceived.incrementAndGet();

        webSocketLastActive = true;
        onMessage(ws, message);
    }

    /**
     * Sets the data channel for this endpoint.
     * @param dataChannel
     */
    void setDataChannel(DataChannel dataChannel)
    {
        DataChannel prevDataChannel = this.dataChannel.get();
        if (prevDataChannel == null)
        {
            this.dataChannel = new WeakReference<>(dataChannel);
            // We install the handler first, otherwise the 'ready' might fire after we check it but before we
            //  install the handler
            dataChannel.onDataChannelEvents(
                    this::notifyTransportChannelConnected);
            if (dataChannel.isReady())
            {
                notifyTransportChannelConnected();
            }
            dataChannel.onDataChannelMessage(this);
        }
        else if (prevDataChannel == dataChannel)
        {
            //TODO: i think we should be able to ensure this doesn't happen,
            // so throwing for now.  if there's a good
            // reason for this, we can make this a no-op
            throw new Error("Re-setting the same data channel");
        }
        else {
            throw new Error("Overwriting a previous data channel!");
        }

    }
}
