package net.tomp2p.connection;

import net.tomp2p.futures.FutureDone;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.message.Message;

public interface HolePunchInitiator {
	
	public static final int NUMBER_OF_HOLES = 3;
	public static final boolean BROADCAST = false;
	public static final int IDLE_UDP_SECONDS = 30;

	public FutureDone<Message> handleHolePunch(final ChannelCreator channelCreator, final int idleUDPSeconds,
			final FutureResponse futureResponse, final Message originalMessage);
}
