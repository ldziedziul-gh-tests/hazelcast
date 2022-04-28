package io.netty.channel.epoll;

import java.nio.ByteBuffer;

public interface EpollReadHandler {

    void init(EpollAsyncSocket asyncSocket);

    void onRead(ByteBuffer receiveBuffer);
}
