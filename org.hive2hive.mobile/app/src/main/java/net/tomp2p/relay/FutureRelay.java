package net.tomp2p.relay;

import net.tomp2p.futures.BaseFutureImpl;

import java.util.Collection;

public class FutureRelay extends BaseFutureImpl<FutureRelay> {

	private Collection<BaseRelayClient> relays;

	private boolean isRelayNotRequired;

	public FutureRelay() {
		self(this);
	}

	public FutureRelay nothingTodo() {
		synchronized (lock) {
			if (!completedAndNotify()) {
				return this;
			}
			type = FutureType.OK;
			isRelayNotRequired = true;
		}
		notifyListeners();
		return this;
	}

	public FutureRelay done(Collection<BaseRelayClient> relays) {
		synchronized (lock) {
			if (!completedAndNotify()) {
				return this;
			}
			type = FutureType.OK;
			this.relays = relays;
		}
		notifyListeners();
		return this;
	}

	public boolean isRelayNotRequired() {
		synchronized (lock) {
			return isRelayNotRequired;
		}
	}

	public Collection<BaseRelayClient> relays() {
		synchronized (lock) {
			return relays;
		}
	}
}
