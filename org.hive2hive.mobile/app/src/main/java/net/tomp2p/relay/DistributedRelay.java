package net.tomp2p.relay;

import net.tomp2p.connection.PeerConnection;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.futures.FutureForkJoin;
import net.tomp2p.futures.FuturePeerConnection;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.relay.buffer.BufferRequestListener;
import net.tomp2p.relay.buffer.BufferedRelayClient;
import net.tomp2p.rpc.RPC;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The relay manager is responsible for setting up and maintaining connections
 * to relay peers and contains all information about the relays.
 * 
 * @author Raphael Voellmy
 * @author Thomas Bocek
 * @author Nico Rutishauser
 * 
 */
public class DistributedRelay implements BufferRequestListener {

	private final static Logger LOG = LoggerFactory.getLogger(DistributedRelay.class);

	private final Peer peer;
	private final RelayRPC relayRPC;

	private final List<BaseRelayClient> relayClients;
	private final Set<PeerAddress> failedRelays;

	private final Collection<RelayListener> relayListeners;
	private final RelayClientConfig relayConfig;

	/**
	 * @param peer
	 *            the unreachable peer
	 * @param relayRPC
	 *            the relay RPC
	 * @param maxFail 
	 * @param relayConfig 
	 * @param maxRelays
	 *            maximum number of relay peers to set up
	 * @param relayType
	 *            the kind of the relay connection
	 */
	public DistributedRelay(final Peer peer, RelayRPC relayRPC, RelayClientConfig relayConfig) {
		this.peer = peer;
		this.relayRPC = relayRPC;
		this.relayConfig = relayConfig;

		relayClients = Collections.synchronizedList(new ArrayList<BaseRelayClient>());
		failedRelays = new ConcurrentCacheSet<PeerAddress>(relayConfig.failedRelayWaitTime());
		relayListeners = Collections.synchronizedList(new ArrayList<RelayListener>(1));
	}

	public RelayClientConfig relayConfig() {
		return relayConfig;
	}

	/**
	 * Returns connections to current relay peers
	 * 
	 * @return List of PeerAddresses of the relay peers (copy)
	 */
	public List<BaseRelayClient> relayClients() {
		synchronized (relayClients) {
			// make a copy
			return Collections.unmodifiableList(new ArrayList<BaseRelayClient>(relayClients));
		}
	}

	public void addRelayListener(RelayListener relayListener) {
		synchronized (relayListeners) {
			relayListeners.add(relayListener);
		}
	}

	public FutureForkJoin<FutureDone<Void>> shutdown() {
		final AtomicReferenceArray<FutureDone<Void>> futureDones;
		synchronized (relayClients) {
			futureDones = new AtomicReferenceArray<FutureDone<Void>>(relayClients.size());
			for (int i = 0; i < relayClients.size(); i++) {
				futureDones.set(i, relayClients.get(i).shutdown());
			}
		}

		synchronized (relayListeners) {
			relayListeners.clear();
		}

		return new FutureForkJoin<FutureDone<Void>>(futureDones);
	}

	/**
	 * Sets up relay connections to other peers. The number of relays to set up
	 * is determined by {@link PeerAddress#MAX_RELAYS} or passed to the
	 * constructor of this class. It is important that we call this after we
	 * bootstrapped and have recent information in our peer map.
	 * 
	 * @return RelayFuture containing a {@link DistributedRelay} instance
	 */
	public FutureRelay setupRelays(final FutureRelay futureRelay) {
		final List<PeerAddress> relayCandidates;
		if (relayConfig.manualRelays().isEmpty()) {
			// Get the neighbors of this peer that could possibly act as relays. Relay
			// candidates are neighboring peers that are not relayed themselves and have
			// not recently failed as relay or denied acting as relay.
			relayCandidates = peer.distributedRouting().peerMap().all();
			// remove those who we know have failed
			relayCandidates.removeAll(failedRelays);
		} else {
			// if the user sets manual relays, the failed relays are not removed, as this has to be done by
			// the user
			relayCandidates = new ArrayList<PeerAddress>(relayConfig.manualRelays());
		}

		filterRelayCandidates(relayCandidates);
		setupPeerConnections(futureRelay, relayCandidates);
		return futureRelay;
	}

	/**
	 * Remove recently failed relays, peers that are relayed themselves and
	 * peers that are already relays
	 */
	private void filterRelayCandidates(Collection<PeerAddress> relayCandidates) {
		for (Iterator<PeerAddress> iterator = relayCandidates.iterator(); iterator.hasNext();) {
			PeerAddress pa = iterator.next();

			// filter peers that are relayed themselves
			if (pa.isRelayed()) {
				iterator.remove();
				continue;
			}

			// filter relays that are already connected
			synchronized (relayClients) {
				for (BaseRelayClient relay : relayClients) {
					if (relay.relayAddress().equals(pa)) {
						iterator.remove();
						break;
					}
				}
			}
		}

		LOG.trace("Found {} addtional relay candidates", relayCandidates.size());
	}

	/**
	 * Sets up N peer connections to relay candidates, where N is maxRelays
	 * minus the current relay count.

	 * @return FutureDone
	 */
	private void setupPeerConnections(final FutureRelay futureRelay, List<PeerAddress> relayCandidates) {
		final int nrOfRelays = Math.min(relayConfig.type().maxRelayCount() - relayClients.size(), relayCandidates.size());
		if (nrOfRelays > 0) {
			LOG.debug("Setting up {} relays", nrOfRelays);

			@SuppressWarnings("unchecked")
			FutureDone<PeerConnection>[] futureDones = new FutureDone[nrOfRelays];
			AtomicReferenceArray<FutureDone<PeerConnection>> relayConnectionFutures = new AtomicReferenceArray<FutureDone<PeerConnection>>(
					futureDones);
			setupPeerConnectionsRecursive(relayConnectionFutures, relayCandidates, nrOfRelays, futureRelay, 0, new StringBuilder());
		} else {
			if (relayCandidates.isEmpty()) {
				// no candidates
				futureRelay.failed("done");
			} else {
				// nothing to do
				futureRelay.done(Collections.<BaseRelayClient> emptyList());
			}
		}
	}

	/**
	 * Sets up connections to relay peers recursively. If the maximum number of
	 * relays is already reached, this method will do nothing.
	 * 
	 * @param futureRelayConnections
	 * @param relayCandidates
	 *            List of peers that could act as relays
	 * @param numberOfRelays
	 *            The number of relays to establish.
	 * @param futureDone
	 * @return
	 * @throws InterruptedException
	 */
	private void setupPeerConnectionsRecursive(final AtomicReferenceArray<FutureDone<PeerConnection>> futures,
			final List<PeerAddress> relayCandidates, final int numberOfRelays, final FutureRelay futureRelay,
			final int fail, final StringBuilder status) {
		int active = 0;
		for (int i = 0; i < numberOfRelays; i++) {
			if (futures.get(i) == null) {
				PeerAddress candidate = null;
				synchronized (relayCandidates) {
					if (!relayCandidates.isEmpty()) {
						candidate = relayCandidates.remove(0);
					}
				}
				if (candidate != null) {
					// contact the candiate and ask for being my relay
					FutureDone<PeerConnection> futureDone = sendMessage(candidate);
					futures.set(i, futureDone);
					active++;
				}
			} else {
				active++;
			}
		}
		if (active == 0) {
			updatePeerAddress();
			futureRelay.failed("No candidates: " + status.toString());
			return;
		} else if (fail > relayConfig.maxFail()) {
			updatePeerAddress();
			futureRelay.failed("Maxfail: " + status.toString());
			return;
		}

		FutureForkJoin<FutureDone<PeerConnection>> ffj = new FutureForkJoin<FutureDone<PeerConnection>>(active, false,
				futures);
		ffj.addListener(new BaseFutureAdapter<FutureForkJoin<FutureDone<PeerConnection>>>() {
			public void operationComplete(FutureForkJoin<FutureDone<PeerConnection>> futureForkJoin) throws Exception {
				if (futureForkJoin.isSuccess()) {
					updatePeerAddress();
					futureRelay.done(relayClients());
				} else if (!peer.isShutdown()) {
					setupPeerConnectionsRecursive(futures, relayCandidates, numberOfRelays, futureRelay, fail + 1,
							status.append(futureForkJoin.failedReason()).append(" "));
				} else {
					futureRelay.failed(futureForkJoin);
				}
			}
		});
	}

	/**
	 * Send the setup-message to the relay peer. If the peer that is asked to act as
	 * relay is relayed itself, the request will be denied.
	 * 
	 * @param candidate the relay's peer address
	 * @param relayConfig 
	 * @return FutureDone with a peer connection to the newly set up relay peer
	 */
	private FutureDone<PeerConnection> sendMessage(final PeerAddress candidate) {
		final FutureDone<PeerConnection> futureDone = new FutureDone<PeerConnection>();

		final Message message = relayRPC.createMessage(candidate, RPC.Commands.RELAY.getNr(), Type.REQUEST_1);

		// depend on the relay type whether to keep the connection open or close it after the setup.
		message.keepAlive(relayConfig.type().keepConnectionOpen());

		// encode the relay type in the message such that the relay node knows how to handle
		message.intValue(relayConfig.type().ordinal());

		// append relay-type specific data
		relayConfig.prepareSetupMessage(message);

		LOG.debug("Setting up relay connection to peer {}, message {}", candidate, message);
		final FuturePeerConnection fpc = peer.createPeerConnection(candidate);
		fpc.addListener(new BaseFutureAdapter<FuturePeerConnection>() {
			public void operationComplete(final FuturePeerConnection futurePeerConnection) throws Exception {
				if (futurePeerConnection.isSuccess()) {
					// successfully created a connection to the relay peer
					final PeerConnection peerConnection = futurePeerConnection.object();

					// send the message
					FutureResponse response = RelayUtils.send(peerConnection, peer.peerBean(), peer.connectionBean(), message);
					response.addListener(new BaseFutureAdapter<FutureResponse>() {
						public void operationComplete(FutureResponse future) throws Exception {
							if (future.isSuccess()) {
								// finialize the relay setup
								setupAddRelays(peerConnection);
								futureDone.done(peerConnection);
							} else {
								LOG.debug("Peer {} denied relay request", candidate);
								failedRelays.add(candidate);
								futureDone.failed(future);
							}
						}
					});
				} else {
					LOG.debug("Unable to setup a connection to relay peer {}", candidate);
					failedRelays.add(candidate);
					futureDone.failed(futurePeerConnection);
				}
			}
		});

		return futureDone;
	}

	/**
	 * Is called when the setup with the relay worked. Adds the relay to the list.
	 */
	private void setupAddRelays(PeerConnection peerConnection) {
		synchronized (relayClients) {
			if (relayClients.size() >= relayConfig.type().maxRelayCount()) {
				LOG.warn("The maximum number ({}) of relays is reached", relayConfig.type().maxRelayCount());
				return;
			}
		}

		BaseRelayClient connection = relayConfig.createClient(peerConnection, peer);
		addCloseListener(connection);
		
		synchronized (relayClients) {
			LOG.debug("Adding peer {} as a relay", peerConnection.remotePeer());
			relayClients.add(connection);
			relayRPC.addClient(connection);
		}
	}

	/**
	 * Adds a close listener for an open peer connection, so that if the
	 * connection to the relay peer drops, a new relay is found and a new relay
	 * connection is established
	 * 
	 * @param connection
	 *            the relay connection on which to add a close listener
	 */
	private void addCloseListener(final BaseRelayClient connection) {
		connection.addCloseListener(new RelayListener() {
			@Override
			public void relayFailed(PeerAddress relayAddress) {
				// used to remove a relay peer from the unreachable peers
				// peer address. It will <strong>not</strong> cut the
				// connection to an existing peer, but only update the
				// unreachable peer's PeerAddress if a relay peer failed.
				// It will also cancel the {@link PeerMapUpdateTask}
				// maintenance task if the last relay is removed.
				relayClients.remove(connection);
				relayRPC.removeClient(connection);
				failedRelays.add(relayAddress);
				updatePeerAddress();

				synchronized (relayListeners) {
					for (RelayListener relayListener : relayListeners) {
						relayListener.relayFailed(relayAddress);
					}
				}
			}
		});
	}

	/**
	 * Updates the peer's PeerAddress: Adds the relay addresses to the peer
	 * address, updates the firewalled flags, and bootstraps to announce its new
	 * relay peers.
	 */
	private void updatePeerAddress() {
		// add relay addresses to peer address
		boolean hasRelays = !relayClients.isEmpty();

		Collection<PeerSocketAddress> socketAddresses = new ArrayList<PeerSocketAddress>(relayClients.size());
		synchronized (relayClients) {
			for (BaseRelayClient relay : relayClients) {
				PeerAddress pa = relay.relayAddress();
				socketAddresses.add(new PeerSocketAddress(pa.inetAddress(), pa.tcpPort(), pa.udpPort()));
			}
		}

		// update firewalled and isRelayed flags
		PeerAddress newAddress = peer.peerAddress().changeFirewalledTCP(!hasRelays).changeFirewalledUDP(!hasRelays)
				.changeRelayed(hasRelays).changePeerSocketAddresses(socketAddresses).changeSlow(hasRelays && relayConfig.type().isSlow());
		peer.peerBean().serverPeerAddress(newAddress);
		LOG.debug("Updated peer address {}, isrelay = {}", newAddress, hasRelays);
	}


	@Override
	public FutureDone<Void> sendBufferRequest(String relayPeerId) {
		for (BaseRelayClient relayConnection : relayClients()) {
			String peerId = relayConnection.relayAddress().peerId().toString();
			if (peerId.equals(relayPeerId) && relayConnection instanceof BufferedRelayClient) {
				return ((BufferedRelayClient) relayConnection).sendBufferRequest();
			}
		}

		LOG.warn("No connection to relay {} found. Ignoring the message.", relayPeerId);
		return new FutureDone<Void>().failed("No connection to relay " + relayPeerId + " found");
	}
}
