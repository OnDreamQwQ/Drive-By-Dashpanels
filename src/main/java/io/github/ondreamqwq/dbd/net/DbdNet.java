package io.github.ondreamqwq.dbd.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Payload registration for the module picker. */
public final class DbdNet {

    private DbdNet() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RequestChannelsPacket.TYPE, RequestChannelsPacket.STREAM_CODEC,
                RequestChannelsPacket::handle);
        registrar.playToServer(SelectTargetPacket.TYPE, SelectTargetPacket.STREAM_CODEC,
                SelectTargetPacket::handle);
        registrar.playToClient(ChannelListPacket.TYPE, ChannelListPacket.STREAM_CODEC,
                ChannelListPacket::handle);
    }
}
