package net.tomp2p.connection;

import net.tomp2p.peers.PeerAddress;

import java.net.InetSocketAddress;

public interface RelaySender {
	
	InetSocketAddress createSocketUPD(PeerAddress peerAddress);
	InetSocketAddress createSocketTCP(PeerAddress peerAddress);

}
