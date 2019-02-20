package com.amazon.opendistroforelasticsearch.common;

import org.elasticsearch.transport.TransportChannel;

public interface AbstractTransportChannel extends TransportChannel {
    public TransportChannel getInnerChannel();
}
