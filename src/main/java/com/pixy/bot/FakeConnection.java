package com.pixy.bot;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** A network connection that goes nowhere. Lets a ServerPlayer exist without a real client. */
public class FakeConnection extends Connection {
    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override public void send(Packet<?> packet) { }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) { }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { }
    @Override public void flushChannel() { }
    @Override public boolean isConnected() { return true; }
    @Override public void setReadOnly() { }
    @Override public void handleDisconnection() { }
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) { }
}
